package com.teliy.tizenpult

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Клиент Samsung TV Remote API (WebSocket, порт 8002, TLS).
 *
 * Совместим с Tizen 5.5 (модели 2021, например UE43AU9000) и новее.
 * Все входящие/исходящие кадры дублируются в onLog — для диагностики на экране.
 */
class SamsungRemote(
    private val host: String,
    private val token: String?,
    private val onStatus: (String) -> Unit,
    private val onToken: (String) -> Unit,
    private val onApps: (List<AppInfo>) -> Unit,
    private val onLog: (String) -> Unit
) {
    data class AppInfo(val appId: String, val name: String)

    private var ws: WebSocket? = null

    // Состояние сеанса (читаются/пишутся из фоновых потоков)
    @Volatile private var launchAck = false         // ТВ подтвердил ed.apps.launch
    @Volatile private var appsReceived = false      // ТВ прислал список приложений
    @Volatile private var imeSeen = false           // клавиатура на ТВ открывалась в сеансе
    @Volatile private var textBroadcastSent = false // broadcast textReceived — 1 раз за сеанс

    private val client: OkHttpClient = unsafeClient()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private fun log(msg: String) {
        onLog("${timeFmt.format(Date())}  $msg")
    }

    /** Скрываем токен в логах */
    private fun mask(s: String): String =
        s.replace(Regex("(\"token\"\\s*:\\s*\")([^\"]+)(\")")) { m ->
            "${m.groupValues[1]}${m.groupValues[2].take(3)}...${m.groupValues[3]}"
        }

    fun connect() {
        val name = Base64.encodeToString("Tizen Pult".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        var url = "wss://$host:8002/api/v2/channels/samsung.remote.control?name=$name"
        val withToken = !token.isNullOrBlank()
        if (withToken) url += "&token=$token"

        launchAck = false
        appsReceived = false
        imeSeen = false
        textBroadcastSent = false

        log(if (withToken) "Подключение к wss://$host:8002 (с сохранённым токеном)" else "Подключение к wss://$host:8002 (без токена — ТВ покажет запрос)")

        val request = Request.Builder().url(url).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                log("WS открыт: HTTP ${response.code}")
                onStatus("Соединение открыто, ждём ответ ТВ...")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                log("◀ ТВ: ${mask(text).take(400)}")
                try {
                    handleMessage(JSONObject(text))
                } catch (e: Exception) {
                    log("!! не удалось разобрать кадр: ${e.message}")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                log("ТВ закрывает соединение: code=$code reason=${reason.ifBlank { "-" }}")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                log("WS закрыт: code=$code")
                onStatus("Отключено — нажмите «Подключ.» для повторного сеанса")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val reason = t.message ?: "нет соединения"
                log("✖ Ошибка WS: $reason")
                onStatus("Ошибка: $reason")
            }
        })
    }

    private fun handleMessage(obj: JSONObject) {
        when (obj.optString("event")) {
            "ms.channel.connect" -> {
                val t = obj.optJSONObject("data")?.optString("token")
                if (!t.isNullOrBlank()) {
                    log("✔ Авторизация пройдена, токен получен (${t.take(3)}...) и сохранён")
                    onToken(t)
                }
                onStatus("Подключено к ТВ ✔")
            }

            "ms.channel.unauthorized" -> {
                log("ТВ отклонил доступ (unauthorized)")
                onStatus("Нажмите «Подключ.» ещё раз и выберите «Разрешить» на ТВ")
            }

            "ed.installedApp.get", "ed.edenAppList.get" -> {
                appsReceived = true
                val apps = parseApps(obj)
                log("Список приложений: ${apps.size} шт.")
                if (apps.isNotEmpty()) onApps(apps)
            }

            "ed.apps.launch" -> {
                val code = obj.optInt("data", -1)
                launchAck = true
                log(if (code == 200) "✔ ТВ подтвердил запуск приложения (код 200)" else "ТВ ответил на запуск приложения: $code")
            }

            // ТВ сообщает об открытии/закрытии экранной клавиатуры:
            // текст принимается только пока клавиатура открыта (поле ввода в фокусе)
            "ms.remote.imeStart" -> {
                imeSeen = true
                log("⌨ ТВ: клавиатура открыта — теперь текст можно отправлять")
            }
            "ms.remote.imeEnd" -> log("ТВ: клавиатура закрыта — текст больше не принимается")

            "ms.error" -> {
                val msg = obj.optJSONObject("data")?.optString("message")
                log("!! ТВ сообщил об ошибке: ${msg ?: obj.toString().take(200)}")
            }

            else -> log("событие: ${obj.optString("event")}")
        }
    }

    private fun parseApps(obj: JSONObject): List<AppInfo> {
        val result = mutableListOf<AppInfo>()
        var arr: JSONArray? = null
        // Формат ответа ТВ (эталон parse_installed_app):
        // {"event":"ed.installedApp.get","from":"host","data":{"data":[{appId,name},...]}}
        try {
            arr = obj.getJSONObject("data").getJSONArray("data")
        } catch (_: Exception) {
        }
        if (arr == null) {
            try {
                arr = obj.getJSONObject("params").getJSONArray("data")
            } catch (_: Exception) {
            }
        }
        if (arr == null) {
            try {
                arr = obj.getJSONArray("data")
            } catch (_: Exception) {
            }
        }
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val a = arr.optJSONObject(i) ?: continue
                val id = a.optString("appId")
                val name = a.optString("name")
                if (id.isNotBlank() && name.isNotBlank()) result.add(AppInfo(id, name))
            }
        }
        return result
    }

    private fun send(payload: JSONObject) {
        val sent = ws?.send(payload.toString()) ?: false
        if (sent) {
            log("▶ К ТВ: ${payload}")
        } else {
            log("!! НЕ отправлено — соединение закрыто. Нажмите «Подключ.»")
            onStatus("Соединение потеряно — нажмите «Подключ.»")
        }
    }

    /** Нажатие кнопки пульта, например KEY_VOLUP, KEY_POWER, KEY_HDMI */
    fun sendKey(key: String) {
        send(JSONObject().apply {
            put("method", "ms.remote.control")
            put("params", JSONObject().apply {
                put("Cmd", "Click")
                put("DataOfCmd", key)
                // Формат как в samsungctl / samsungtvws (Home Assistant):
                // Option — строка "false", а не JSON-объект {"false": false}
                put("Option", "false")
                put("TypeOfRemote", "SendRemoteKey")
            })
        })
    }

    /**
     * Ввод текста — эталонная WS-последовательность (samsung-tv-ws-api send_text/end_text):
     * 1) broadcast custom.remote.textReceived — один раз за сеанс,
     * 2) SendInputString {Cmd=base64(текст), DataOfCmd="base64"},
     * 3) SendInputEnd — завершает сеанс ввода (без него часть прошивок не показывает текст).
     *
     * ВАЖНО: ТВ принимает текст только пока открыта экранная клавиатура (поле ввода в
     * фокусе). Открытие/закрытие видно в журнале: ⌨ ms.remote.imeStart / ms.remote.imeEnd.
     */
    fun sendTextSmart(text: String) {
        if (text.isEmpty()) return
        Thread {
            val b64 = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

            if (!textBroadcastSent) {
                send(JSONObject().apply {
                    put("method", "ms.channel.emit")
                    put("params", JSONObject().apply {
                        put("event", "custom.remote.textReceived")
                        put("to", "broadcast")
                    })
                })
                textBroadcastSent = true
            }

            send(JSONObject().apply {
                put("method", "ms.remote.control")
                put("params", JSONObject().apply {
                    put("Cmd", b64)
                    put("DataOfCmd", "base64")
                    put("TypeOfRemote", "SendInputString")
                })
            })

            send(JSONObject().apply {
                put("method", "ms.remote.control")
                put("params", JSONObject().apply {
                    put("TypeOfRemote", "SendInputEnd")
                })
            })

            if (!imeSeen) {
                log("Подсказка: ТВ не сообщал об открытой клавиатуре в этом сеансе. " +
                    "Откройте поле ввода на ТВ (поиск YouTube и т.п.), дождитесь ⌨ в журнале и повторите")
            }
        }.start()
    }

    /**
     * Запуск приложения по appId (11101200001 — YouTube, org.tizen.netflixapp — Netflix).
     * На прошивках 2021 (Tizen 5.5/6.0) запуск по WS часто молча игнорируется, поэтому
     * каскад: WS DEEP_LINK → WS NATIVE_LAUNCH → REST POST /api/v2/applications/{appId}.
     * Подтверждение ТВ — ответное событие ed.apps.launch с кодом 200.
     */
    fun launchApp(appId: String) {
        launchAck = false
        sendLaunchWs(appId, "DEEP_LINK")
        Thread {
            if (awaitLaunchAck(3000)) return@Thread

            log("ТВ не подтвердил DEEP_LINK — пробую NATIVE_LAUNCH")
            sendLaunchWs(appId, "NATIVE_LAUNCH")
            if (awaitLaunchAck(3000)) return@Thread

            log("WS-запуск игнорируется прошивкой 2021 — пробую REST POST /api/v2/applications/$appId")
            val t = token
            var code = restRequest("applications/$appId", "POST")
            if (code == 401 && !t.isNullOrBlank()) {
                code = restRequest("applications/$appId?token=$t", "POST")
            }
            if (code in 200..299) {
                log("✔ Приложение запущено через REST API (HTTP $code)")
            } else {
                log("REST-запуск ответил HTTP $code — приложение может отсутствовать или ТВ не отвечает")
            }
        }.start()
    }

    private fun sendLaunchWs(appId: String, actionType: String) {
        send(JSONObject().apply {
            put("method", "ms.channel.emit")
            put("params", JSONObject().apply {
                put("event", "ed.apps.launch")
                put("to", "host")
                put("data", JSONObject().apply {
                    // Формат эталона: action_type + appId + metaTag (может быть пустым)
                    put("action_type", actionType)
                    put("appId", appId)
                    put("metaTag", "")
                })
            })
        })
    }

    private fun awaitLaunchAck(timeoutMs: Long): Boolean {
        var waited = 0L
        while (!launchAck && waited < timeoutMs) {
            Thread.sleep(200)
            waited += 200
        }
        return launchAck
    }

    /**
     * Список приложений. На Tizen 5+ (2020+) Samsung отключила ответ на
     * ed.installedApp.get (issue #23 эталонной библиотеки), поэтому если ТВ молчит —
     * REST-проверка известных приложений: GET /api/v2/applications/{appId}.
     */
    fun requestApps() {
        appsReceived = false
        send(JSONObject().apply {
            put("method", "ms.channel.emit")
            put("params", JSONObject().apply {
                put("event", "ed.installedApp.get")
                put("to", "host")
            })
        })
        Thread {
            Thread.sleep(4000)
            if (appsReceived) return@Thread

            log("ТВ молчит: на Tizen 5+ список по WS отключён Samsung (issue #23 эталона)")
            log("REST-проверка известных приложений (GET /api/v2/applications/{id})...")
            val known = listOf(
                "11101200001" to "YouTube",
                "org.tizen.netflixapp" to "Netflix",
                "org.tizen.browser" to "Интернет",
                "com.amazon.amazonvideo.livingroom" to "Prime Video",
                "org.tizen.samsungtvplus" to "Samsung TV Plus"
            )
            val found = mutableListOf<AppInfo>()
            val t = token
            for ((id, name) in known) {
                var code = restRequest("applications/$id", "GET")
                if (code == 401 && !t.isNullOrBlank()) {
                    code = restRequest("applications/$id?token=$t", "GET")
                }
                if (code in 200..299) {
                    found.add(AppInfo(id, name))
                    log("✔ $name установлен (HTTP $code)")
                }
            }
            if (found.isNotEmpty()) {
                onApps(found)
                log("Найдено установленных: ${found.size} из ${known.size} проверенных")
            } else {
                log("REST-проверка ничего не нашла — ТВ не отвечает и на REST (коды выше)")
            }
        }.start()
    }

    /** Простой REST-запрос к ТВ (порт 8001). Возвращает HTTP-код, -1 при сетевой ошибке. */
    private fun restRequest(route: String, method: String): Int {
        return try {
            val conn = URL("http://$host:8001/api/v2/$route").openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            val code = conn.responseCode
            conn.disconnect()
            code
        } catch (e: Exception) {
            log("!! REST $method /$route: ${e.message}")
            -1
        }
    }

    fun close() {
        try {
            ws?.close(1000, "bye")
        } catch (_: Exception) {
        }
        ws = null
    }

    /** ТВ использует самоподписанный сертификат — доверяем ему в рамках локальной сети */
    private fun unsafeClient(): OkHttpClient {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ssl = SSLContext.getInstance("TLS")
        ssl.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        return OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .sslSocketFactory(ssl.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    companion object {
        /**
         * Wake-on-LAN: пробуждение ТВ из режима сна.
         * На ТВ должно быть включено: Настройки -> Общие -> Сеть -> Пробуждение по сети.
         */
        fun wakeOnLan(mac: String): Boolean {
            return try {
                val macBytes = mac.split(":", "-")
                    .map { it.trim().toInt(16).toByte() }
                    .toByteArray()
                val payload = ByteArray(6 + 16 * 6)
                for (i in 0 until 6) payload[i] = 0xFF.toByte()
                for (i in 0 until 16) System.arraycopy(macBytes, 0, payload, 6 + i * 6, 6)
                val socket = DatagramSocket()
                val packet = DatagramPacket(
                    payload, payload.size,
                    InetAddress.getByName("255.255.255.255"), 9
                )
                socket.broadcast = true
                socket.send(packet)
                socket.close()
                true
            } catch (e: Exception) {
                false
            }
        }
    }
}
