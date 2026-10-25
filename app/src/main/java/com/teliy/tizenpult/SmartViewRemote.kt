package com.teliy.tizenpult

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Протокол Smart View 2.0 для Samsung Smart TV 2014-2015 (Orsay: H-серия и часть J) —
 * например UE48H6800. Именно его используют эти ТВ (Tizen-канал ws://8001 у них
 * принимает подключение, но отклоняет ms.remote.control — «unrecognized method»).
 *
 * Этапы (эталон xchwarze/samsung-tv-ws-api):
 *  1. Сопряжение по PIN (один раз):
 *     POST http://IP:8080/ws/apps/CloudPINPage  (pin4) — ТВ показывает 4 цифры;
 *     GET  .../ws/pairing?step=0&app_id=12345&device_id=UUID&type=1;
 *     пользователь вводит PIN;
 *     POST .../ws/pairing?step=1 (GeneratorServerHello) -> токен + SKPrime;
 *     POST .../ws/pairing?step=2 (ServerAckMsg) -> session_id;
 *     DELETE .../ws/apps/CloudPINPage/run.
 *  2. Команды:
 *     GET  http://IP:8000/socket.io/1/?t=...  -> идентификатор приложения;
 *     WS   ws://IP:8000/socket.io/1/websocket/<id> + кадр «1::/com.samsung.companion»;
 *     команды: AES-128-ECB(токен) от JSON {"method":"POST","body":{plugin:RemoteControl,…}}
 *     в кадре 5::/com.samsung.companion:{"name":"callCommon",…}.
 *
 * Токен и session_id сохраняются («sv:<ip>») — повторное сопряжение не требуется.
 */
class SmartViewRemote(
    private val host: String,
    private val savedAuth: String?,            // «tokenHex|sessionId» или null
    private val deviceId: String,              // стабильный UUID для device_id сопряжения
    private val onStatus: (String) -> Unit,
    private val onLog: (String) -> Unit,
    private val onAuth: (Boolean) -> Unit,
    private val onTransport: (Boolean) -> Unit,
    private val onSavedAuth: (String) -> Unit, // сохранить «tokenHex|sessionId»
    private val onClosed: () -> Unit,
    private val requestPin: (retry: Boolean, submit: (String?) -> Unit) -> Unit,
    private val onFrame: (String) -> Unit = { _ -> }   // расшифрованное тело ответа ТВ (для разбора событий)
) {
    companion object {
        private const val USER_ID = "654321"
        private const val APP_ID = "12345"
        /** Кандидаты портов: сопряжение (REST) и команды (socket.io) */
        private val REST_PORTS = intArrayOf(8080, 8000)
        private val WS_PORTS = intArrayOf(8000, 8080)
    }

    @Volatile private var ws: WebSocket? = null
    @Volatile private var tokenHex: String? = null
    @Volatile private var sessionId: String? = null
    @Volatile private var authOk = false
    @Volatile private var stopping = false
    @Volatile private var restPortChosen = 0

    /** REST-порт, найденный при подключении (8080/8000) — для Orsay-запросов приложений */
    fun chosenRestPort(): Int = restPortChosen

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private fun log(msg: String) = onLog("${timeFmt.format(Date())}  $msg")

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    fun connect() {
        Thread {
            try {
                // Раскладка сохранённой авторизации
                if (!savedAuth.isNullOrBlank() && savedAuth.contains("|")) {
                    tokenHex = savedAuth.substringBefore("|")
                    sessionId = savedAuth.substringAfter("|")
                    log("Smart View 2.0: используем сохранённое сопряжение (токен ${tokenHex?.take(8)}...)")
                }

                val restPort = pickRestPort()
                    ?: run {
                        log("✖ Smart View 2.0: нет ответа на портах 8080/8000 (REST)")
                        onStatus("Старый ТВ не ответил на порту 8080/8000")
                        fail()
                        return@Thread
                    }
                log("Smart View 2.0: REST-порт $restPort")
                restPortChosen = restPort

                if (tokenHex == null) {
                    if (!pairWithPin(restPort)) {
                        fail()
                        return@Thread
                    }
                }

                openCommandChannel(restPort)
            } catch (e: Exception) {
                log("✖ Smart View 2.0: ${e.message}")
                onStatus("Ошибка старого протокола: ${e.message}")
                fail()
            }
        }.start()
    }

    private fun fail() {
        stopping = true
        onTransport(false)
        onAuth(false)
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
        ws = null
    }

    /** Клавиша: {"plugin": "RemoteControl", "param2": "Click", "param3": KEY, …} */
    fun sendKey(key: String) {
        val w = ws
        if (!authOk || w == null) {
            log("!! нет сеанса Smart View 2.0 — нажмите «Подключ.»")
            return
        }
        // формат json.dumps (как в эталоне): пробелы после «:» и «,» — байт-в-байт
        // с эталонным тест-вектором команды (KEY_POWER, 192 байта)
        val payload = "{\"method\": \"POST\", \"body\": {\"plugin\": \"RemoteControl\", " +
            "\"param1\": \"uuid:$APP_ID\", \"param2\": \"Click\", \"param3\": \"$key\", " +
            "\"param4\": false, \"api\": \"SendRemoteKey\", \"version\": \"1.000\"}}"
        val frame = encryptFrame(payload)
        val sent = w.send(frame)
        log(if (sent) "▶ $key (Smart View 2.0)" else "!! НЕ отправлено — соединение закрыто")
    }

    /**
     * Произвольная команда в callCommon-кадре (тот же шифроконтейнер, что у клавиш).
     * Используется для проб API, которых нет в эталоне (например, ed.installedApp.get):
     * ответ ТВ всё равно придёт кадром 5:: и будет расшифрован в onMessage → onFrame.
     */
    fun emitRaw(bodyJson: String) {
        val w = ws
        if (!authOk || w == null) {
            log("!! нет сеанса Smart View 2.0 — нажмите «Подключ.»")
            return
        }
        val sent = w.send(encryptFrame(bodyJson))
        log(if (sent) "▶ callCommon: ${bodyJson.take(100)}" else "!! НЕ отправлено — соединение закрыто")
    }

    fun close() {
        stopping = true
        onTransport(false)
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
        ws = null
    }

    // ---------- 1. Сопряжение по PIN ----------

    private fun pairWithPin(restPort: Int): Boolean {
        // Страница PIN на ТВ
        val (code, page) = http(restPort, "GET", "ws/apps/CloudPINPage", null)
        val state = Regex("state>([^<>]*)", RegexOption.IGNORE_CASE).find(page)?.groupValues?.get(1)?.trim()
        log("CloudPINPage: HTTP $code, state=${state ?: "?"}")
        if (state == null || state == "stopped") {
            val (pcode, _) = http(restPort, "POST", "ws/apps/CloudPINPage", "pin4")
            log("Запрос PIN на ТВ: HTTP $pcode (на экране ТВ появится 4-значный PIN)")
        } else {
            log("PIN уже отображается на ТВ")
        }
        onStatus("Введите PIN, который появился на экране ТВ")

        // Шаг 1 с PIN (до 3 попыток). Шаг 0 — перед КАЖДОЙ попыткой, как в эталоне
        // (try_pin() = шаг 0 + шаг 1): после неудачной попытки ТВ ждёт нового шага 0,
        // иначе шаг 2 отклоняется с HTTP 403 (сеанс сопряжения не сброшен).
        var result: SmartViewCrypto.ClientHelloResult? = null
        for (attempt in 1..3) {
            val pin = askPin(retry = attempt > 1) ?: run {
                log("Ввод PIN отменён")
                onStatus("Сопряжение отменено")
                closePinPage(restPort)
                return false
            }
            val step0 = http(restPort, "GET", pairingUrl(0) + "&type=1", null)
            log("Сопряжение шаг 0: HTTP ${step0.first}")
            val hello = SmartViewCrypto.generateServerHello(USER_ID, pin)
            val content = "{\"auth_Data\":{\"auth_type\":\"SPC\",\"GeneratorServerHello\":\"" +
                hello.serverHelloHex + "\"}}"
            val (code1, resp1) = http(restPort, "POST", pairingUrl(1), content)
            log("Сопряжение шаг 1: HTTP $code1")
            if (code1 !in 200..299) {
                log("ТВ отклонил ServerHello (HTTP $code1): ${resp1.take(160)}")
                continue
            }
            val clientHello = Regex(
                "GeneratorClientHello[^0-9a-fA-F]*([0-9a-fA-F]{200,})",
                RegexOption.IGNORE_CASE
            ).find(resp1)?.groupValues?.get(1)
            if (clientHello == null) {
                log("В ответе ТВ нет GeneratorClientHello: ${resp1.take(120)}")
                continue
            }
            result = SmartViewCrypto.parseClientHello(clientHello, hello.dataHash, hello.aesKey, USER_ID)
            if (result != null) break
            log("PIN неверный — ТВ не подтвердил (хэш не совпал)")
        }
        val auth = result ?: run {
            onStatus("PIN не принят — попробуйте ещё раз («Подключ.»)")
            closePinPage(restPort)
            return false
        }

        // Шаг 2 — взаимное подтверждение
        val serverAck = SmartViewCrypto.generateServerAcknowledge(auth.skPrime)
        val content2 = "{\"auth_Data\":{\"auth_type\":\"SPC\",\"request_id\":\"0\",\"ServerAckMsg\":\"" +
            serverAck + "\"}}"
        val (code2, resp2) = http(restPort, "POST", pairingUrl(2), content2)
        log("Сопряжение шаг 2: HTTP $code2")
        if (code2 !in 200..299) {
            log("✖ ТВ отклонил подтверждение сопряжения: ${resp2.take(160)}")
            onStatus("ТВ отклонил подтверждение — нажмите «Подключ.» и введите PIN заново")
            closePinPage(restPort)
            return false
        }
        // auth_data — JSON-поле или сырой текст ответа (как в эталоне)
        val authData = try {
            JSONObject(resp2).optString("auth_data", "").ifBlank { resp2 }
        } catch (_: Exception) { resp2 }
        if (authData.isBlank()) {
            log("✖ ТВ вернул пустой auth_data — окно PIN истекло, повторите сопряжение")
            onStatus("Окно PIN истекло — нажмите «Подключ.» и введите PIN заново")
            closePinPage(restPort)
            return false
        }
        val clientAck = Regex(
            "ClientAckMsg[^0-9a-fA-F]*([0-9a-fA-F]{40,})",
            RegexOption.IGNORE_CASE
        ).find(authData)?.groupValues?.get(1)
        val session = Regex("session_id[^0-9]*(\\d+)", RegexOption.IGNORE_CASE)
            .find(authData)?.groupValues?.get(1)
        if (clientAck == null || session == null) {
            log("✖ В ответе нет ClientAckMsg/session_id: ${resp2.take(160)}")
            onStatus("Неожиданный ответ ТВ — попробуйте сопряжение заново")
            closePinPage(restPort)
            return false
        }
        val expectedAck = SmartViewCrypto.expectedClientAcknowledge(auth.skPrime)
        if (!clientAck.equals(expectedAck, ignoreCase = true)) {
            log("✖ ClientAckMsg ТВ не совпал с ожидаемым")
            onStatus("ТВ не подтвердил сеанс — попробуйте сопряжение заново")
            closePinPage(restPort)
            return false
        }

        // Закрыть страницу PIN на ТВ
        closePinPage(restPort)

        tokenHex = auth.ctxHex
        sessionId = session
        onSavedAuth("${auth.ctxHex}|$session")
        log("✔ Сопряжение завершено: токен ${auth.ctxHex.take(8)}..., session_id=$session — сохранено")
        return true
    }

    private fun pairingUrl(step: Int): String =
        "ws/pairing?step=$step&app_id=$APP_ID&device_id=$deviceId"

    /** Закрыть страницу PIN на ТВ (тихо; вызывается и при ошибках сопряжения) */
    private fun closePinPage(restPort: Int) {
        http(restPort, "DELETE", "ws/apps/CloudPINPage/run", null, quiet = true)
    }

    private fun askPin(retry: Boolean): String? {
        val latch = CountDownLatch(1)
        var pin: String? = null
        requestPin(retry) { p ->
            pin = p
            latch.countDown()
        }
        latch.await(180, TimeUnit.SECONDS)
        return pin?.takeIf { it.isNotBlank() }
    }

    // ---------- 2. Командный канал (socket.io, порт 8000) ----------

    private fun openCommandChannel(restPort: Int) {
        // Идентификатор приложения из socket.io-рукопожатия: «id:hb:close:transports»
        var wsPort = restPort
        var app = ""
        for (port in WS_PORTS) {
            val (code, body) = http(port, "GET", "socket.io/1/?t=${System.currentTimeMillis()}", null)
            if (code in 200..299 && body.isNotBlank()) {
                wsPort = port
                app = body.split(":").firstOrNull()?.trim() ?: ""
                log("socket.io на порту $port: id=$app")
                break
            }
        }
        if (app.isBlank()) {
            log("✖ socket.io не ответил на портах 8000/8080")
            onStatus("Командный канал старого ТВ недоступен")
            fail()
            return
        }

        val url = "ws://$host:$wsPort/socket.io/1/websocket/$app"
        log("Открываю командный канал: ws://$host:$wsPort …")
        val request = Request.Builder().url(url).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onTransport(true)
                log("Канал открыт (HTTP ${response.code}) — вход в /com.samsung.companion")
                webSocket.send("1::/com.samsung.companion")
                // небольшой запас на кадр подтверждения — затем сеанс готов
                Thread {
                    Thread.sleep(400)
                    if (!stopping && !authOk) markConnected()
                }.start()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                when {
                    text.startsWith("2::") -> {
                        // heartbeat socket.io — обязаны отвечать
                        webSocket.send("2::")
                    }
                    text.startsWith("1::") -> markConnected()
                    // ответы ТВ шифрованы токеном сеанса — расшифровываем для журнала
                    // и отдаём верхнему уровню (разбор ed.*-событий, если прошивка умеет)
                    text.startsWith("5::/com.samsung.companion:") -> {
                        val dec = decryptFrameText(text)
                        log("◀ ТВ: ${dec.take(220)}")
                        onFrame(dec)
                    }
                    else -> log("◀ ТВ: ${text.take(200)}")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                log("Канал Smart View 2.0 закрыт (code=$code)")
                shutdown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!stopping) {
                    log("✖ Ошибка канала: ${t.message}")
                    shutdown()
                }
            }
        })
    }

    private fun markConnected() {
        if (authOk) return
        authOk = true
        log("✔ Smart View 2.0 подключён — клавиши работают")
        onStatus("Подключено к ТВ (старый протокол) ✔")
        onAuth(true)
    }

    private fun shutdown() {
        authOk = false
        onTransport(false)
        if (!stopping) {
            onClosed()
        }
    }

    // ---------- шифрование команд ----------

    /** Расшифровка ответа ТВ (кадр 5::…receiveCommon) токеном сеанса: AES-128-ECB + PKCS7 */
    private fun decryptFrameText(frame: String): String {
        return try {
            val args = JSONObject(frame.substringAfter("companion:")).optString("args")
            val parts = args.removeSurrounding("[", "]").split(",")
            val data = ByteArray(parts.size) { (parts[it].trim().toInt() and 0xFF).toByte() }
            val c = Cipher.getInstance("AES/ECB/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(hexToBytes(tokenHex ?: ""), "AES"))
            val dec = c.doFinal(data)
            val pad = (dec.lastOrNull()?.toInt() ?: 0) and 0xFF
            val clean = if (pad in 1..16 && dec.size > pad) dec.copyOfRange(0, dec.size - pad) else dec
            String(clean, Charsets.UTF_8)
        } catch (e: Exception) {
            "— (не расшифровано: ${e.message ?: "?"})"
        }
    }

    /** 5::/com.samsung.companion:{"name":"callCommon","args":[{"Session_Id":N,"body":"[байты]"}]} */
    private fun encryptFrame(json: String): String {
        val key = hexToBytes(tokenHex ?: "")
        val data = json.toByteArray(Charsets.UTF_8)
        val padLen = BLOCK - (data.size % BLOCK)
        val padded = data + ByteArray(padLen) { padLen.toByte() }
        val c = Cipher.getInstance("AES/ECB/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        val enc = c.doFinal(padded)
        val ints = StringBuilder()
        for (i in enc.indices) {
            if (i > 0) ints.append(',')
            ints.append(enc[i].toInt() and 0xFF)
        }
        return "5::/com.samsung.companion:{\"name\":\"callCommon\",\"args\":[{\"Session_Id\":" +
            (sessionId ?: "1") + ",\"body\":\"[" + ints + "]\"}]}"
    }

    private val BLOCK = 16

    private fun hexToBytes(hex: String): ByteArray {
        val n = hex.length / 2
        val out = ByteArray(n)
        for (i in 0 until n) out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        return out
    }

    // ---------- HTTP ----------

    /** REST-запрос к ТВ; возвращает (код, тело); порт выбирается из REST_PORTS */
    private fun pickRestPort(): Int? {
        for (port in REST_PORTS) {
            val (code, _) = http(port, "GET", "ws/apps/CloudPINPage", null, quiet = true)
            if (code > 0) return port  // любой ответ HTTP = REST-сервер жив
        }
        return null
    }

    private fun http(
        port: Int,
        method: String,
        route: String,
        body: String?,
        quiet: Boolean = false
    ): Pair<Int, String> {
        return try {
            val conn = URL("http://$host:$port/$route").openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = 5000
            conn.readTimeout = 8000
            if (body != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.toByteArray(Charsets.UTF_8).size)
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText() ?: ""
            conn.disconnect()
            if (!quiet) log("HTTP $code ← $method /$route")
            code to resp
        } catch (e: Exception) {
            if (!quiet) log("✖ HTTP $method /$route: ${e.message}")
            -1 to ""
        }
    }
}
