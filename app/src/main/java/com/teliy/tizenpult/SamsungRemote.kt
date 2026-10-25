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
                val apps = parseApps(obj)
                log("Список приложений: ${apps.size} шт.")
                if (apps.isNotEmpty()) onApps(apps)
            }

            else -> log("событие: ${obj.optString("event")}")
        }
    }

    private fun parseApps(obj: JSONObject): List<AppInfo> {
        val result = mutableListOf<AppInfo>()
        var arr: JSONArray? = null
        try {
            arr = obj.getJSONObject("params").getJSONArray("data")
        } catch (_: Exception) {
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
     * Умный ввод текста: сначала REST IME API (официальный путь для ТВ 2021+, где
     * WS-текст отключён), при неудаче — WS-форматы в фоновом потоке.
     */
    fun sendTextSmart(text: String) {
        if (text.isEmpty()) return
        Thread {
            val ok = sendTextIme(text)
            if (!ok) {
                log("IME недоступен — отправляю WS-форматы (ТВ 2016–2019)")
                sendTextWs(text)
            }
        }.start()
    }

    /**
     * Ввод текста через REST IME API: POST http://IP:8001/api/v2/remoteControl/imeInput/{base64}?token={ws-токен}
     * Токен — тот же, что получен после «Разрешить» на экране ТВ (используется библиотекой
     * samsung-tv-ws-api для паролей на ТВ 2021+). true = ТВ принял текст (HTTP 200).
     */
    private fun sendTextIme(text: String): Boolean {
        val t = token
        if (t.isNullOrBlank()) {
            log("IME: нет сохранённого токена — нажмите «Подключ.» и «Разрешить» на ТВ")
            return false
        }
        return try {
            val b64 = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val conn = URL("http://$host:8001/api/v2/remoteControl/imeInput/$b64?token=$t")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            val code = conn.responseCode
            conn.disconnect()
            log(if (code == 200) "✔ Текст доставлен (IME API, HTTP $code)" else "IME API ответил HTTP $code")
            code == 200
        } catch (e: Exception) {
            log("!! IME ошибка: ${e.message}")
            false
        }
    }

    /** Ввод текста по WebSocket: новый формат ТВ 2021+ и legacy для 2016–2019 */
    private fun sendTextWs(text: String) {
        if (text.isEmpty()) return
        val b64 = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

        // 1) Broadcast «текст принят к обработке» — обязательный на ТВ 2021+
        send(JSONObject().apply {
            put("method", "ms.channel.emit")
            put("params", JSONObject().apply {
                put("event", "custom.remote.textReceived")
                put("to", "broadcast")
            })
        })

        // 2) Новый формат SendInputString (ТВ 2021+)
        send(JSONObject().apply {
            put("method", "ms.remote.control")
            put("params", JSONObject().apply {
                put("Cmd", b64)
                put("DataOfCmd", "base64")
                put("TypeOfRemote", "SendInputString")
            })
        })

        // 3) Legacy-формат (ТВ 2016–2019)
        send(JSONObject().apply {
            put("method", "ms.remote.control")
            put("params", JSONObject().apply {
                put("Cmd", "SendInput")
                put("DataOfCmd", b64)
                put("TypeOfRemote", "SendInput")
            })
        })
    }

    /** Запуск приложения по appId (например 11101200001 — YouTube) */
    fun launchApp(appId: String, actionType: String = "NATIVE_LAUNCH") {
        send(JSONObject().apply {
            put("method", "ms.channel.emit")
            put("params", JSONObject().apply {
                put("event", "ed.apps.launch")
                put("to", "host")
                put("data", JSONObject().apply {
                    put("appId", appId)
                    put("action_type", actionType)
                })
            })
        })
    }

    /** Запрос списка установленных приложений */
    fun requestApps() {
        send(JSONObject().apply {
            put("method", "ms.channel.emit")
            put("params", JSONObject().apply {
                put("event", "ed.installedApp.get")
                put("to", "host")
            })
        })
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
