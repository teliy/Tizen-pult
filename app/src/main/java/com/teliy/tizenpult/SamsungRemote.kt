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
import java.net.InetAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Клиент Samsung TV Remote API (WebSocket, порт 8002, TLS).
 *
 * Совместим с Tizen 5.5 (модели 2021, например UE43AU9000) и новее.
 * Протокол: wss://<ip>:8002/api/v2/channels/samsung.remote.control
 *
 * При первом подключении без токена ТВ показывает запрос «Разрешить».
 * После подтверждения приходит событие ms.channel.connect с токеном —
 * его нужно сохранить и передавать при следующих подключениях.
 */
class SamsungRemote(
    private val host: String,
    private val token: String?,
    private val onStatus: (String) -> Unit,
    private val onToken: (String) -> Unit,
    private val onApps: (List<AppInfo>) -> Unit
) {
    data class AppInfo(val appId: String, val name: String)

    private var ws: WebSocket? = null
    private val client: OkHttpClient = unsafeClient()

    fun connect() {
        val name = Base64.encodeToString("Tizen Pult".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        var url = "wss://$host:8002/api/v2/channels/samsung.remote.control?name=$name"
        if (!token.isNullOrBlank()) url += "&token=$token"

        val request = Request.Builder().url(url).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onStatus("Соединение открыто, ждём ответ ТВ...")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    handleMessage(JSONObject(text))
                } catch (_: Exception) {
                    // игнорируем нераспознанные кадры
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val reason = t.message ?: "нет соединения"
                onStatus("Ошибка: $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onStatus("Отключено")
            }
        })
    }

    private fun handleMessage(obj: JSONObject) {
        when (obj.optString("event")) {
            "ms.channel.connect" -> {
                val t = obj.optJSONObject("data")?.optString("token")
                if (!t.isNullOrBlank()) onToken(t)
                onStatus("Подключено к ТВ ✔")
            }

            "ms.channel.unauthorized" -> {
                onStatus("Доступ не подтверждён: нажмите «Подключ.» ещё раз и выберите «Разрешить» на ТВ")
            }

            "ed.installedApp.get", "ed.edenAppList.get" -> {
                val apps = parseApps(obj)
                if (apps.isNotEmpty()) onApps(apps)
            }
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
        ws?.send(payload.toString())
    }

    /** Нажатие кнопки пульта, например KEY_VOLUP, KEY_POWER, KEY_HDMI_1 */
    fun sendKey(key: String) {
        send(JSONObject().apply {
            put("method", "ms.remote.control")
            put("params", JSONObject().apply {
                put("Cmd", "Click")
                put("DataOfCmd", key)
                put("Option", JSONObject().put("false", false))
                put("TypeOfRemote", "SendRemoteKey")
            })
        })
    }

    /** Ввод текста в активное поле на ТВ (поле должно быть в фокусе) */
    fun sendText(text: String) {
        send(JSONObject().apply {
            put("method", "ms.remote.control")
            put("params", JSONObject().apply {
                put("Cmd", "SendInput")
                put(
                    "DataOfCmd",
                    Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                )
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
