package com.teliy.tizenpult

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
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
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Клиент Samsung TV Remote API.
 *
 * Поддерживаемые протоколы (proto):
 *  - «wss» — wss://host:8002 с токеном: Tizen 2018+ (UE43AU9000 и другие новые);
 *  - «ws»  — ws://host:8001 без TLS: Tizen 2016–2017 (с пробой полноценности сеанса:
 *    старые Orsay-ТВ 2014–2015 подключение принимают, но управление отклоняют);
 *  - «legacy» / «legacy-aes» — TCP 55000: Smart TV 2011–2015 (см. LegacyRemote);
 *  - «smart» — Smart View 2.0 (socket.io :8000 + PIN-сопряжение :8080): Orsay
 *    H-серия 2014 и часть J 2015 (UE48H6800 и т.п.), см. SmartViewRemote.
 *
 * Все входящие/исходящие кадры дублируются в onLog — для диагностики на экране.
 */
class SamsungRemote(
    private val host: String,
    private val proto: String = "wss",
    private val token: String?,
    private val smartAuth: String? = null,      // «tokenHex|sessionId» для proto=«smart»
    private val deviceId: String = "",          // UUID для сопряжения Smart View 2.0
    private val onStatus: (String) -> Unit,
    private val onToken: (String) -> Unit,
    private val onApps: (List<AppInfo>, Boolean) -> Unit,   // список + признак «каталог» (не подтверждён ТВ)
    private val onLog: (String) -> Unit,
    private val onSmartAuth: ((String) -> Unit)? = null,
    private val onPin: ((retry: Boolean, submit: (String?) -> Unit) -> Unit)? = null,
    /** v3.0: что сейчас на ТВ (канал/приложение) — пустая строка = скрыть */
    private val onTvNow: ((String) -> Unit)? = null
) {
    data class AppInfo(val appId: String, val name: String)

    private var ws: WebSocket? = null
    private var legacy: LegacyRemote? = null
    private var smart: SmartViewRemote? = null

    /** Сеанс подтверждён ТВ (ms.channel.connect / handshake 55000) */
    @Volatile private var connected = false

    /** Транспорт открыт (WS upgrade / TCP 55000) — можно ждать «Разрешить» на ТВ */
    @Volatile private var transportAlive = false

    /** ws://8001 проверен и отклонён ТВ (старая прошивка Orsay) — сеанс бесполезен */
    @Volatile private var rejected = false

    /** Проба API после ws-подключения: ждали ответы на emits (см. connect) */
    @Volatile private var wsProbeActive = false
    @Volatile private var wsProbeErrors = 0

    /** Для ws://8001 «подключено» = пробы API прошли (Orsay принимает connect, но отклоняет API) */
    fun isConnected(): Boolean = connected && !wsProbeActive
    fun isTransportAlive(): Boolean = transportAlive
    fun isRejected(): Boolean = rejected
    private fun isLegacy(): Boolean = proto == "legacy" || proto == "legacy-aes"
    private fun isSmart(): Boolean = proto == "smart"

    // Состояние сеанса (читаются/пишутся из фоновых потоков)
    @Volatile private var launchAck = false         // ТВ подтвердил ed.apps.launch
    @Volatile private var appsReceived = false      // ТВ прислал список приложений
    @Volatile private var imeSeen = false           // клавиатура на ТВ открывалась в сеансе
    @Volatile private var textBroadcastSent = false // broadcast textReceived — 1 раз за сеанс
    @Volatile private var unauthRetried = false     // уже переподключались без токена
    /** Буфер для requestApps: WS-ответы собираются сюда, а не уходят в UI поодиночке */
    @Volatile private var pendingApps: MutableList<AppInfo>? = null

    /** Буфер для requestAppsOrsay: ed-события, пришедшие через smart-канал */
    @Volatile private var pendingSmartApps: MutableList<AppInfo>? = null

    /** Первый Move мыши печатаем в журнал, дальнейшие идут тихо (иначе лог забивается) */
    @Volatile private var mouseMoveLogged = false

    /** ТВ использует самоподписанный сертификат — доверяем ему в рамках локальной сети */
    private val trustManager: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private val client: OkHttpClient = unsafeClient()
    // Отдельный клиент для REST-проверок: короткие таймауты,
    // чтобы проба десятка ID не растянулась на минуту
    private val fastClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .sslSocketFactory(client.sslSocketFactory, trustManager)
        .hostnameVerifier { _, _ -> true }
        .build()
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
        launchAck = false
        appsReceived = false
        imeSeen = false
        textBroadcastSent = false
        unauthRetried = false
        rejected = false
        wsProbeActive = false
        wsProbeErrors = 0
        mouseMoveLogged = false

        if (isLegacy()) {
            val lr = LegacyRemote(
                host = host,
                encrypted = proto == "legacy-aes",
                onStatus = onStatus,
                onLog = onLog,
                onAuth = { ok -> connected = ok },
                onTransport = { up -> transportAlive = up },
                onClosed = {
                    connected = false
                    transportAlive = false
                    onTvNow?.invoke("")
                    onStatus("Отключено — нажмите POWER (вкл/выкл) или «Подключ.»")
                }
            )
            legacy = lr
            lr.connect()
            return
        }

        if (isSmart()) {
            val sv = SmartViewRemote(
                host = host,
                savedAuth = smartAuth,
                deviceId = deviceId,
                onStatus = onStatus,
                onLog = onLog,
                onAuth = { ok -> connected = ok },
                onTransport = { up -> transportAlive = up },
                onSavedAuth = { auth -> onSmartAuth?.invoke(auth) },
                onClosed = {
                    connected = false
                    transportAlive = false
                    onTvNow?.invoke("")
                    onStatus("Отключено — нажмите POWER (вкл/выкл) или «Подключ.»")
                },
                requestPin = { retry, submit -> onPin?.invoke(retry, submit) },
                onFrame = { dec -> onSmartFrame(dec) }
            )
            smart = sv
            sv.connect()
            return
        }

        openWs(withToken = !token.isNullOrBlank())
    }

    /** Открытие Tizen-WS (wss:8002 / ws:8001); withToken=false — после unauthorized */
    private fun openWs(withToken: Boolean) {
        val scheme = if (proto == "ws") "ws" else "wss"
        val port = if (proto == "ws") 8001 else 8002
        val name = Base64.encodeToString("Tizen Pult".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        var url = "$scheme://$host:$port/api/v2/channels/samsung.remote.control?name=$name"
        val usingToken = withToken && !token.isNullOrBlank()
        if (usingToken) url += "&token=$token"

        log(if (usingToken) "Подключение к $scheme://$host:$port (с сохранённым токеном)" else "Подключение к $scheme://$host:$port (без токена — ТВ покажет запрос)")

        val request = Request.Builder().url(url).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                transportAlive = true
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
                connected = false
                transportAlive = false
                onTvNow?.invoke("")
                onStatus("Отключено — нажмите POWER (вкл/выкл) или «Подключ.»")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                transportAlive = false
                onTvNow?.invoke("")
                val reason = t.message ?: "нет соединения"
                log("✖ Ошибка WS: $reason")
                onStatus("Ошибка: $reason")
            }
        })
    }

    private fun handleMessage(obj: JSONObject) {
        when (obj.optString("event")) {
            "ms.channel.connect" -> {
                connected = true
                val t = obj.optJSONObject("data")?.optString("token")
                if (!t.isNullOrBlank()) {
                    log("✔ Авторизация пройдена, токен получен (${t.take(3)}...) и сохранён")
                    onToken(t)
                }
                // v3.0: часть прошивок кладёт в connect текущий канал/приложение — покажем
                emitTvChannel(obj.optJSONObject("data"))
                // Проба полноценности сеанса для ws://8001: старые ТВ (Orsay 2014-2015)
                // принимают подключение, но отклоняют ms.remote.control. Сразу шлём два
                // API-запроса: если оба вернут ms.error — сеанс бесполезен, маркируем reject.
                if (proto == "ws") {
                    wsProbeActive = true
                    wsProbeErrors = 0
                    send(JSONObject().apply {
                        put("method", "ms.channel.emit")
                        put("params", JSONObject().apply {
                            put("event", "ed.installedApp.get")
                            put("to", "host")
                        })
                    })
                    send(JSONObject().apply {
                        put("method", "ms.channel.emit")
                        put("params", JSONObject().apply {
                            put("event", "ed.edenAppList.get")
                            put("to", "host")
                        })
                    })
                }
                onStatus("Подключено к ТВ ✔")
            }

            "ms.channel.unauthorized" -> {
                connected = false
                log("ТВ отклонил доступ (unauthorized)")
                // Чужой/устаревший токен (например, унаследованный от другой прошивки)
                // — переподключаемся без него, ТВ покажет «Разрешить»
                if (!token.isNullOrBlank() && !unauthRetried) {
                    unauthRetried = true
                    log("Переподключаюсь без токена — подтвердите «Разрешить» на ТВ")
                    try { ws?.close(1000, "retry") } catch (_: Exception) {}
                    openWs(withToken = false)
                } else {
                    onStatus("Нажмите «Подключ.» ещё раз и выберите «Разрешить» на ТВ")
                }
            }

            "ed.installedApp.get", "ed.edenAppList.get" -> {
                appsReceived = true
                val apps = parseApps(obj)
                if (wsProbeActive) {
                    // Проба ws://8001 пройдена: ТВ отвечает как полноценный Tizen
                    wsProbeActive = false
                    log("проба API: ТВ ответил списком (${apps.size} шт.) — сеанс полноценный")
                }
                val buf = pendingApps
                if (buf != null) {
                    buf.addAll(apps)
                    log("WS-список: +${apps.size} шт.")
                } else {
                    log("Список приложений: ${apps.size} шт.")
                    if (apps.isNotEmpty()) onApps(apps, false)
                }
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
                if (wsProbeActive) {
                    val m = msg ?: ""
                    val orsayLike = m.contains("unrecognized", true) ||
                        m.contains("session' of null", true) || m.contains("session of null", true)
                    if (orsayLike) {
                        wsProbeErrors++
                        if (wsProbeErrors >= 2) {
                            wsProbeActive = false
                            rejected = true
                            log("✖ оба API-запроса отклонены — ws://8001 не поддерживает управление (старая прошивка). Пробую следующий протокол")
                            try { ws?.close(1000, "probe") } catch (_: Exception) {}
                            connected = false
                            transportAlive = false
                        }
                    }
                }
            }

            // ТВ сообщает о смене канала — обновляем «На ТВ: …» (v3.0)
            "ms.channel.channelChanged" -> emitTvChannel(obj.optJSONObject("data"))

            // ТВ включает/выключает touch-режим (браузер и приложения с курсором).
            // Это уведомление о состоянии: протокол мыши (ProcessMouseDevice) не меняется.
            "ms.remote.touchEnable" -> log("ТВ: touch-режим включён — текущее приложение принимает мышь/тачпад")
            "ms.remote.touchDisable" -> log("ТВ: touch-режим выключен")

            else -> log("событие: ${obj.optString("event")}")
        }
    }

    /**
     * Расшифрованный кадр smart-канала (Orsay). В эталоне у callCommon известен
     * только plugin RemoteControl (клавиши), но часть прошивок может отвечать
     * и на ed.*-события — пробуем и их: если в кадре пришло событие списка
     * приложений, разбираем его так же, как на Tizen.
     */
    private fun onSmartFrame(dec: String) {
        val evt = Regex("\"event\"\\s*:\\s*\"([^\"]+)\"").find(dec)?.groupValues?.get(1) ?: return
        if (evt != "ed.installedApp.get" && evt != "ed.edenAppList.get") return
        val apps = try { parseApps(JSONObject(dec)) } catch (_: Exception) { emptyList() }
        if (apps.isEmpty()) return
        appsReceived = true
        val buf = pendingSmartApps
        if (buf != null) {
            buf.addAll(apps)
            log("smart-канал: ed-событие принесло список +${apps.size} шт.")
        } else {
            log("smart-канал: ed-событие принесло список ${apps.size} шт.")
            onApps(apps, false)
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
                // visible:false — скрытые/служебные записи, пользователю они не нужны
                if (!a.optBoolean("visible", true)) continue
                val id = a.optString("appId")
                val name = a.optString("name")
                if (id.isNotBlank() && name.isNotBlank()) result.add(AppInfo(id, name))
            }
        }
        return result
    }

    private fun send(payload: JSONObject, quiet: Boolean = false) {
        val sent = ws?.send(payload.toString()) ?: false
        if (sent) {
            if (!quiet) log("▶ К ТВ: ${payload}")
        } else {
            log("!! НЕ отправлено — соединение закрыто. Нажмите «Подключ.»")
            onStatus("Соединение потеряно — нажмите «Подключ.»")
        }
    }

    /** Есть ли в текущем сеансе настоящий курсор (ProcessMouseDevice) — только Tizen wss/ws */
    fun supportsMouse(): Boolean = connected && !isLegacy() && !isSmart()

    /**
     * Сдвинуть курсор ТВ (тачпад, режим мыши).
     *
     * ВАЖНО (v2.9): Position.x/y — это ДЕЛЬТА сдвига, а НЕ абсолютная позиция!
     * Реверс официальной библиотеки Samsung multiscreen (SmartThings/Smart View,
     * класс RemoteControl): в Position кладётся currentMoveDelta — сдвиг с прошлого
     * кадра. v2.8 слала абсолютные координаты 1920×1080 — ТВ трактовал их как сдвиг,
     * курсор улетал за край экрана и замирал (симптом: «курсор пропал, движения нет»).
     * Time — строка epoch-миллисекунд, как в офиц. либе ("" + System.currentTimeMillis()).
     * Move-кадры в лог не пишутся (тихо) — до 25 кадров/с забили бы журнал мгновенно.
     */
    fun sendMouseMove(dx: Int, dy: Int) {
        if (!supportsMouse()) return
        if (!mouseMoveLogged) {
            log("▶ мышь: ProcessMouseDevice Move — дельта ($dx,$dy); дальнейшие Move в журнал не пишутся")
            mouseMoveLogged = true
        }
        send(JSONObject().apply {
            put("method", "ms.remote.control")
            put("params", JSONObject().apply {
                put("Cmd", "Move")
                put("Position", JSONObject().apply {
                    put("x", dx)
                    put("y", dy)
                    put("Time", System.currentTimeMillis().toString())
                })
                put("TypeOfRemote", "ProcessMouseDevice")
            })
        }, quiet = true)
    }

    /**
     * Вытащить из payload-а ТВ информацию о текущем канале (если прошивка её кладёт)
     * и показать в статусе «На ТВ: …». Формат недокументирован — парсим свободно:
     * data.currentChannel / data.channel с полями channelName/major/chanNum.
     */
    private fun emitTvChannel(d: JSONObject?) {
        if (d == null) return
        val ch = d.optJSONObject("currentChannel") ?: d.optJSONObject("channel") ?: return
        val name = ch.optString("channelName").ifBlank { ch.optString("name") }
        val major = ch.optInt("major", 0)
        val num = if (major > 0) major.toString()
                  else ch.optString("major").ifBlank { ch.optString("chanNum") }
        if (name.isBlank() && num.isBlank()) return
        val label = when {
            num.isNotBlank() && name.isNotBlank() -> "канал $num — $name"
            num.isNotBlank() -> "канал $num"
            else -> name
        }
        log("На ТВ: $label")
        onTvNow?.invoke(label)
    }

    /** Клик левой кнопкой мыши в текущей позиции курсора (тачпад, режим мыши) */
    fun sendMouseClick() {
        if (!supportsMouse()) return
        log("▶ мышь: LeftClick в позиции курсора")
        send(JSONObject().apply {
            put("method", "ms.remote.control")
            put("params", JSONObject().apply {
                put("Cmd", "LeftClick")
                put("TypeOfRemote", "ProcessMouseDevice")
            })
        })
    }

    /** Клик правой кнопкой мыши в текущей позиции курсора (тачпад — удержание, v3.0).
     *  Формат подтверждён samsungctl: Cmd=RightClick, TypeOfRemote=ProcessMouseDevice. */
    fun sendMouseRightClick() {
        if (!supportsMouse()) return
        log("▶ мышь: RightClick в позиции курсора")
        send(JSONObject().apply {
            put("method", "ms.remote.control")
            put("params", JSONObject().apply {
                put("Cmd", "RightClick")
                put("TypeOfRemote", "ProcessMouseDevice")
            })
        })
    }

    /** Нажатие кнопки пульта, например KEY_VOLUP, KEY_POWER, KEY_HDMI */
    fun sendKey(key: String) {
        if (isSmart()) {
            smart?.sendKey(key)
            return
        }
        if (isLegacy()) {
            legacy?.sendKey(key)
            return
        }
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
        if (isLegacy() || isSmart()) {
            log("Ввод текста недоступен по старому протоколу (${if (isSmart()) "Smart View 2.0" else "55000"})")
            onStatus("На старых ТВ (до 2016) ввод текста недоступен")
            return
        }
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
                log("Подсказка: ТВ принимает текст только когда открыто поле ввода. " +
                    "Откройте на ТВ поиск (HOME → Поиск или значок лупы), поставьте курсор в строку и повторите отправку")
                onStatus("⌨ Откройте поле ввода/поиск на ТВ и повторите отправку")
            }
        }.start()
    }

    /**
     * Запуск приложения по appId (111299001912 — YouTube, 11101200001 — Netflix).
     * Каскад: WS DEEP_LINK → WS NATIVE_LAUNCH → REST POST (http:8001, затем https:8002).
     * Подтверждение ТВ — ответное событие ed.apps.launch с кодом 200.
     * v3.0: data — дополнительный payload для ed.apps.launch (например {url:…} для браузера).
     */
    fun launchApp(appId: String, name: String? = null, data: JSONObject? = null) {
        if (isLegacy() || isSmart()) {
            launchAppOrsay(appId, name)
            return
        }
        launchAck = false
        sendLaunchWs(appId, "DEEP_LINK", data)
        Thread {
            if (awaitLaunchAck(3000)) {
                if (data != null || name != null) onTvNow?.invoke(name ?: appId)
                return@Thread
            }

            log("ТВ не подтвердил DEEP_LINK — пробую NATIVE_LAUNCH")
            sendLaunchWs(appId, "NATIVE_LAUNCH", data)
            if (awaitLaunchAck(3000)) {
                if (data != null || name != null) onTvNow?.invoke(name ?: appId)
                return@Thread
            }

            log("WS-запуск не подтверждён — пробую REST POST (порт 8001, затем 8002)")
            val t = token
            var code = restGet("http", 8001, "applications/$appId", "POST").first
            if (code == 401 && !t.isNullOrBlank()) {
                code = restGet("http", 8001, "applications/$appId?token=$t", "POST").first
            }
            if (code in 200..299) {
                log("✔ Приложение запущено (REST 8001, HTTP $code)")
                onTvNow?.invoke(name ?: appId)
                return@Thread
            }
            var code2 = restGet("https", 8002, "applications/$appId", "POST").first
            if (code2 == 401 && !t.isNullOrBlank()) {
                code2 = restGet("https", 8002, "applications/$appId?token=$t", "POST").first
            }
            if (code2 in 200..299) {
                log("✔ Приложение запущено (REST 8002, HTTP $code2)")
                onTvNow?.invoke(name ?: appId)
            } else {
                log("REST не сработал: 8001=$code, 8002=$code2 — возможно, приложение не установлено или эндпоинта нет на этой прошивке")
            }
        }.start()
    }

    private fun sendLaunchWs(appId: String, actionType: String, data: JSONObject? = null) {
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
                    // v3.0: дополнительный payload (url и т.п.) — дописываем к data
                    if (data != null) {
                        for (k in data.keys()) {
                            data.opt(k)?.let { put(k, it) }
                        }
                    }
                })
            })
        })
    }

    /**
     * v3.0: WS-запуск приложения с payload-ом (без REST-фолбэка — запуск REST-ом
     * потерял бы payload). Перебирает кандидатов appId: DEEP_LINK+data → NATIVE_LAUNCH+data.
     * При полном неуспехе зовётся onFail (например, фолбэк на браузер для YouTube).
     */
    private fun launchWsAppWithPayload(
        appIds: List<String>,
        label: String,
        data: JSONObject,
        onFail: (() -> Unit)? = null
    ) {
        if (isLegacy() || isSmart()) {
            log("Запуск «$label» недоступен по старому протоколу (только Tizen 2016+)")
            onStatus("Это работает только на Tizen (2016+)")
            return
        }
        launchAck = false
        Thread {
            for (id in appIds) {
                sendLaunchWs(id, "DEEP_LINK", data)
                if (awaitLaunchAck(2500)) {
                    log("✔ ТВ подтвердил запуск — $label (id=$id, DEEP_LINK)")
                    onTvNow?.invoke(label)
                    return@Thread
                }
                sendLaunchWs(id, "NATIVE_LAUNCH", data)
                if (awaitLaunchAck(2500)) {
                    log("✔ ТВ подтвердил запуск — $label (id=$id, NATIVE_LAUNCH)")
                    onTvNow?.invoke(label)
                    return@Thread
                }
                log("ТВ не подтвердил запуск id=$id — пробую следующего кандидата")
            }
            if (onFail != null) {
                log("Запуск приложения с payload не подтверждён — использую запасной путь")
                onFail.invoke()
            } else {
                onStatus("ТВ не подтвердил запуск — подробности в журнале")
            }
        }.start()
    }

    /**
     * v3.0: открыть ссылку в браузере ТВ (Tizen). Кандидаты — известные app-ID
     * браузера разных поколений (org.tizen.browser на 2016+, числовые — старее/новее).
     * payload {url:…} внутри ed.apps.launch — проверенный сообществом приём.
     */
    fun launchBrowserUrl(url: String) {
        launchWsAppWithPayload(
            listOf("org.tizen.browser", "3201907018784", "3202010022079", "3201512001"),
            "Браузер: $url",
            JSONObject().put("url", url)
        )
    }

    /**
     * v3.0 (эксперимент): YouTube-видео сразу в ТВ-приложение YouTube (9Ur5IyzjvP —
     * ID на прошивках 2017+). Приём — Cobalt deep link: payload
     * {COBALT_LAUNCH_TYPE:"url", url:"https://www.youtube.com/tv?v=ID"};
     * если прошивка не подтвердила — фолбэк: та же ссылка в браузере ТВ.
     * Не ссылка и не ID → это тоже браузер.
     */
    fun launchYouTubeVideo(raw: String) {
        val id = extractYouTubeId(raw)
        if (id == null) {
            log("Не похоже на ссылку/ID YouTube — открываю в браузере ТВ")
            launchBrowserUrl(raw)
            return
        }
        val tvUrl = "https://www.youtube.com/tv?v=$id"
        launchWsAppWithPayload(
            listOf("9Ur5IyzjvP"),
            "YouTube: $id",
            JSONObject().put("COBALT_LAUNCH_TYPE", "url").put("url", tvUrl)
        ) {
            launchBrowserUrl(tvUrl)
        }
    }

    private fun extractYouTubeId(raw: String): String? {
        val s = raw.trim()
        for (p in listOf(
            Regex("youtu\\.be/([A-Za-z0-9_-]{6,20})"),
            Regex("[?&]v=([A-Za-z0-9_-]{6,20})"),
            Regex("shorts/([A-Za-z0-9_-]{6,20})"),
            Regex("embed/([A-Za-z0-9_-]{6,20})")
        )) {
            p.find(s)?.let { return it.groupValues[1] }
        }
        if (Regex("^[A-Za-z0-9_-]{6,20}$").matches(s) && !s.contains('.')) return s
        return null
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
     * Список приложений — ВСЕ стратегии сливаются в один список (а не каскадом):
     * 1) WS ed.installedApp.get + ed.edenAppList.get (полный список, если прошивка отвечает);
     * 2) REST GET /api/v2/applications (на Tizen 5+ отдаёт только «fetchable» — их мало);
     * 3) REST-проверка известных приложений по ID параллельно (таблицы сообщества:
     *    tavicu/homebridge-samsung-tizen, rs.ltd) — 200 = установлено.
     * Раньше каскад останавливался на первом непустом результате — из-за этого
     * REST-список из 2 приложений («fetchable») перекрывал всё остальное.
     */
    fun requestApps() {
        if (isLegacy() || isSmart()) {
            requestAppsOrsay()
            return
        }
        appsReceived = false
        val wsBuffer = mutableListOf<AppInfo>()
        pendingApps = wsBuffer

        // Стратегия 1: оба WS-события (разные прошивки отвечают на разные)
        send(JSONObject().apply {
            put("method", "ms.channel.emit")
            put("params", JSONObject().apply {
                put("event", "ed.installedApp.get")
                put("to", "host")
            })
        })
        send(JSONObject().apply {
            put("method", "ms.channel.emit")
            put("params", JSONObject().apply {
                put("event", "ed.edenAppList.get")
                put("to", "host")
            })
        })

        Thread {
            // Ждём оба WS-ответа (если придут)
            Thread.sleep(4000)
            pendingApps = null

            val found = mutableListOf<AppInfo>()
            val seenIds = mutableSetOf<String>()
            val seenNames = mutableSetOf<String>()
            val wsCount = wsBuffer.size

            fun addApp(a: AppInfo) {
                val nm = a.name.ifBlank { a.appId }
                if (seenIds.add(a.appId) && seenNames.add(nm.lowercase(Locale.ROOT))) {
                    found.add(AppInfo(a.appId, nm))
                }
            }

            // Стратегия 2: REST-список (мало приложений, но тоже в общий котёл)
            var restCount = 0
            for ((scheme, port) in listOf("http" to 8001, "https" to 8002)) {
                val (code, body) = restGet(scheme, port, "applications")
                if (code in 200..299) {
                    val list = parseAppJson(body)
                    restCount += list.size
                    for (a in list) addApp(a)
                }
            }
            log("Список: WS → $wsCount шт., REST → $restCount шт., объединено → ${found.size}")

            // Стратегия 3: известные ID — ВСЕГДА, параллельно в 8 потоков
            log("Проверяю ${KNOWN_APPS.size} известных ID (200 = установлено)…")
            val t = token
            val probeHits = Collections.synchronizedList(mutableListOf<AppInfo>())
            val pool = Executors.newFixedThreadPool(8)
            val futures = KNOWN_APPS.map { (id, name) ->
                pool.submit {
                    var (code, body) = restGet("http", 8001, "applications/$id", quiet = true)
                    if (code == 401 && !t.isNullOrBlank()) {
                        val r = restGet("http", 8001, "applications/$id?token=$t", quiet = true)
                        code = r.first; body = r.second
                    }
                    if (code == -1) {
                        var r = restGet("https", 8002, "applications/$id", quiet = true)
                        code = r.first; body = r.second
                        if (code == 401 && !t.isNullOrBlank()) {
                            r = restGet("https", 8002, "applications/$id?token=$t", quiet = true)
                            code = r.first; body = r.second
                        }
                    }
                    if (code in 200..299) {
                        val nameFromBody = try { JSONObject(body).optString("name") } catch (_: Exception) { "" }
                        val clean = nameFromBody.ifBlank { name }.substringBefore(" (")
                        probeHits.add(AppInfo(id, clean))
                    }
                }
            }
            for (f in futures) {
                try {
                    f.get(15, TimeUnit.SECONDS)
                } catch (_: Exception) {
                }
            }
            pool.shutdownNow()
            for (a in probeHits) addApp(a)
            log("Пробы известных ID: найдено ${probeHits.size}")

            // Если совсем пусто — один подробный запрос для диагностики в журнале
            if (found.isEmpty()) {
                restGet("http", 8001, "applications/org.tizen.browser")
            }

            if (found.isNotEmpty()) {
                found.sortWith(compareBy { it.name.lowercase(Locale.ROOT) })
                log("✔ Итого приложений: ${found.size}")
                onApps(found, false)
            } else {
                log("Список получить не удалось — пришлите журнал целиком: по кодам 404/401/-1 видно, что отвечает прошивка")
                onApps(emptyList(), false)
            }
        }.start()
    }

    /**
     * Orsay (2011–2015): LIST-эндпоинта протокол не имеет (в эталоне xchwarze
     * encrypted-приложения отсутствуют вовсе). v2.7 — СЛИЯНИЕ всех стратегий
     * (как на Tizen), а не каскад с остановкой на первом непустом:
     *  1) ed.installedApp.get / ed.edenAppList.get через smart-канал — часть
     *     прошивок 2015+ может отвечать на Tizen-события (кадр в журнал);
     *  2) корень /ws/apps/ — на части прошивок отдаёт XML-список DIAL-приложений
     *     (тело пишем в журнал всегда — для диагностики);
     *  3) /api/v2/applications на 8001→8002 — у H-серии частично жив /api/v2,
     *     код ответа виден в журнале;
     *  4) пробы DIAL-имён (GET /ws/apps/YouTube подтверждён на Orsay —
     *    home-assistant/core#117694) и известных ID параллельно в 8 потоков.
     * Если ничего не подтвердилось — КАТАЛОГ известных приложений: каждый запуск
     * POST-ом честно проверит установку (GET может 404-ить там, где POST сработает).
     */
    private fun requestAppsOrsay() {
        Thread {
            val port = orsayPort()
            onStatus("Ищу приложения на ТВ (REST :$port)…")
            log("Orsay: приложения — smart-канал, корень /ws/apps/, /api/v2, ${ORSAY_DIAL_NAMES.size} DIAL-имён + ${ORSAY_KNOWN_APPS.size} ID…")

            val found = mutableListOf<AppInfo>()
            val seenIds = mutableSetOf<String>()
            val seenNames = mutableSetOf<String>()
            fun addApp(a: AppInfo) {
                val nm = a.name.ifBlank { a.appId }
                if (seenIds.add(a.appId) && seenNames.add(nm.lowercase(Locale.ROOT))) {
                    found.add(AppInfo(a.appId, nm))
                }
            }

            // Стратегия 1: ed.*-события через smart-канал (2,5 с на ответ ТВ)
            var smartCount = 0
            val s = smart
            if (s != null && isConnected()) {
                val smartApps = mutableListOf<AppInfo>()
                pendingSmartApps = smartApps
                s.emitRaw("{\"method\": \"ms.channel.emit\", \"params\": {\"event\": \"ed.installedApp.get\", \"to\": \"host\"}}")
                s.emitRaw("{\"method\": \"ms.channel.emit\", \"params\": {\"event\": \"ed.edenAppList.get\", \"to\": \"host\"}}")
                Thread.sleep(2500)
                pendingSmartApps = null
                smartCount = smartApps.size
                for (a in smartApps) addApp(a)
            } else {
                log("smart-канал не активен — ed-проба пропущена")
            }

            // Стратегия 2: корень /ws/apps/ (тело ответа — в журнал всегда)
            var rootCount = 0
            try {
                val (rc, rb) = orsayRest(port, "ws/apps/", "GET")
                if (rb.isNotBlank()) {
                    log("↳ тело /ws/apps/: ${rb.take(400).replace('\n', ' ')}")
                }
                if (rc in 200..299) {
                    val parsed = parseOrsayAppList(rb)
                    rootCount = parsed.size
                    for (a in parsed) addApp(a)
                }
            } catch (_: Exception) {}

            // Стратегия 3: /api/v2/applications (8001, затем 8002) —
            // отказ тоже полезен: код в журнале показывает решение прошивки
            var apiCount = 0
            for ((scheme, p) in listOf("http" to 8001, "https" to 8002)) {
                val (code, body) = restGet(scheme, p, "applications")
                if (code in 200..299) {
                    val list = parseAppJson(body)
                    apiCount += list.size
                    for (a in list) addApp(a)
                }
            }

            // Стратегия 4: проба DIAL-имён и известных ID параллельно в 8 потоков
            val targets = (ORSAY_DIAL_NAMES.map { it to it } + ORSAY_KNOWN_APPS).distinctBy { it.first }
            val hits = Collections.synchronizedList(mutableListOf<AppInfo>())
            val firstMiss = Collections.synchronizedList(mutableListOf<String>())
            val pool = Executors.newFixedThreadPool(8)
            val futures = targets.map { (id, name) ->
                pool.submit {
                    val (code, body) = orsayRest(port, "ws/apps/$id", "GET", quiet = true)
                    if (code in 200..299) {
                        // имя из ответа ТВ (<name>/<title>/<appname>), иначе — из нашей таблицы
                        val nm = Regex("<(name|title|appname)>\\s*([^<]+?)\\s*</", RegexOption.IGNORE_CASE)
                            .find(body)?.groupValues?.get(2)?.trim().takeUnless { it.isNullOrBlank() } ?: name
                        hits.add(AppInfo(id, nm))
                    } else if (body.isNotBlank() && firstMiss.isEmpty()) {
                        firstMiss.add("HTTP $code: ${body.take(120).replace('\n', ' ')}")
                    }
                }
            }
            for (f in futures) {
                try { f.get(30, TimeUnit.SECONDS) } catch (_: Exception) {}
            }
            pool.shutdownNow()
            firstMiss.firstOrNull()?.let { log("↳ пример отказа ТВ: $it") }
            for (a in hits) addApp(a)

            log("Поиск приложений: smart-канал → $smartCount, /ws/apps/ → $rootCount, /api/v2 → $apiCount, пробы имён/ID → ${hits.size}; уникальных → ${found.size}")

            val list = found.distinctBy { it.name.lowercase(Locale.ROOT) }
                .sortedBy { it.name.lowercase(Locale.ROOT) }
            if (list.isNotEmpty()) {
                log("✔ Найдено установленных: ${list.size}")
                onStatus("Найдено приложений: ${list.size}")
                onApps(list, false)
            } else {
                log("✖ Ни один ID/имя не подтвердился — LIST-эндпоинта на Orsay нет; открываю каталог (запуск POST-ом проверит установку)")
                val catalog = ORSAY_KNOWN_APPS
                    .distinctBy { it.second.lowercase(Locale.ROOT) }
                    .map { AppInfo(it.first, it.second) }
                    .sortedBy { it.name.lowercase(Locale.ROOT) }
                onStatus("Каталог приложений — запуск покажет, установлено ли")
                onApps(catalog, true)
            }
        }.start()
    }

    /** Разбор возможного XML-списка приложений (формат не документирован — парсим свободно) */
    private fun parseOrsayAppList(body: String): List<AppInfo> {
        if (!body.contains('<')) return emptyList()
        val out = mutableListOf<AppInfo>()
        val block = Regex("(?is)<app\\b[^>]*>(.*?)</app\\s*>")
        val attrId = Regex("(?is)<app\\b[^>]*\\bid\\s*=\\s*[\"']([^\"']+)[\"']")
        for (m in block.findAll(body)) {
            val b = m.groupValues[1]
            val id = Regex("(?is)<(?:appid|id|applicationid)>\\s*([A-Za-z0-9._-]+)\\s*</")
                .find(b)?.groupValues?.get(1)
                ?: attrId.find(m.value)?.groupValues?.get(1)
            val name = Regex("(?is)<(?:appname|name|title)>\\s*([^<]+?)\\s*</")
                .find(b)?.groupValues?.get(1)?.trim()
            if (!id.isNullOrBlank()) out.add(AppInfo(id, (name ?: id).ifBlank { id }))
        }
        return out
    }

    /**
     * Запуск приложения на Orsay. Каскад: POST /ws/apps/<id>/run →
     * POST /ws/apps/<id> → DIAL-стиль по имени (POST /ws/apps/<имя>/run и /ws/apps/<имя>) —
     * часть прошивок адресует приложения именем пути (как YouTube в DIAL).
     */
    private fun launchAppOrsay(appId: String, name: String? = null) {
        Thread {
            val port = orsayPort()
            var (code, body) = orsayRest(port, "ws/apps/$appId/run", "POST")
            if (code !in 200..299) {
                // часть прошивок запускает POST-ом самого приложения (как CloudPINPage)
                val r = orsayRest(port, "ws/apps/$appId", "POST")
                code = r.first; body = r.second
            }
            val nm = name?.trim()?.takeUnless { it.isEmpty() || it.equals(appId, true) }
            if (code !in 200..299 && nm != null) {
                val nmEnc = URLEncoder.encode(nm, "UTF-8").replace("+", "%20")
                log("ID не сработал — пробую DIAL-запуск по имени «$nm»…")
                var r = orsayRest(port, "ws/apps/$nmEnc/run", "POST")
                code = r.first; body = r.second
                if (code !in 200..299) {
                    r = orsayRest(port, "ws/apps/$nmEnc", "POST")
                    code = r.first; body = r.second
                }
            }
            if (code in 200..299) {
                log("✔ Приложение запущено (HTTP $code)${if (body.isNotBlank()) " :: " + body.take(120).replace('\n', ' ') else ""}")
                onTvNow?.invoke(name ?: appId)
                onStatus("Приложение запускается…")
            } else {
                log("✖ Запуск не удался (HTTP $code) — приложение не установлено или ID не подходит этой прошивке")
                onStatus("Не удалось запустить приложение (HTTP $code)")
            }
        }.start()
    }

    @Volatile private var orsayRestPort = 0

    /** REST-порт Orsay-поверхности /ws/: у smart берём найденный при сопряжении, иначе 8080/8000 */
    private fun orsayPort(): Int {
        if (orsayRestPort != 0) return orsayRestPort
        val fromSmart = if (isSmart()) smart?.chosenRestPort()?.takeIf { it > 0 } else null
        if (fromSmart != null) { orsayRestPort = fromSmart; return fromSmart }
        for (p in intArrayOf(8080, 8000)) {
            val (code, _) = orsayRest(p, "ws/apps/1112990019", "GET", quiet = true)
            if (code > 0) { orsayRestPort = p; return p }
        }
        orsayRestPort = 8080
        return orsayRestPort
    }

    /** REST-запрос к Orsay-поверхности /ws/ (БЕЗ префикса /api/v2, порт 8080/8000) */
    private fun orsayRest(port: Int, route: String, method: String, quiet: Boolean = false): Pair<Int, String> {
        return try {
            val conn = URL("http://$host:$port/$route").openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = 3000
            conn.readTimeout = 6000
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText() ?: ""
            conn.disconnect()
            if (!quiet || code !in 200..299) {
                val bodyPart = if (body.isNotBlank()) " :: " + body.take(160).replace('\n', ' ') else ""
                log("↳ HTTP $code ← $method /$route$bodyPart")
            }
            code to body
        } catch (e: Exception) {
            if (!quiet) log("!! $method /$route: ${e.message}")
            -1 to ""
        }
    }

    /** Разбор возможных форматов REST-списка: {"data":[…]}, {"data":{"data":[…]}}, [ … ] */
    private fun parseAppJson(body: String): List<AppInfo> {
        val out = mutableListOf<AppInfo>()
        try {
            val root = JSONObject(body)
            var arr: JSONArray? = null
            for (key in listOf("data", "dataList", "results")) {
                when (val v = root.opt(key)) {
                    is JSONArray -> { arr = v; break }
                    is JSONObject -> {
                        val inner = v.optJSONArray("data")
                        if (inner != null) { arr = inner; break }
                    }
                }
            }
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val a = arr.optJSONObject(i) ?: continue
                    if (!a.optBoolean("visible", true)) continue // скрытые/служебные
                    val id = a.optString("appId").ifBlank { a.optString("id") }
                    val name = a.optString("name").ifBlank { a.optString("appName") }
                    if (id.isNotBlank()) out.add(AppInfo(id, name.ifBlank { id }))
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    /**
     * REST-запрос к ТВ через OkHttp (доверяет самоподписанному сертификату ТВ —
     * важно для https:8002). Каждый вызов пишет в журнал строку вида
     * «↳ HTTP 404 ← http:8001 GET /api/v2/applications/... :: тело» — для диагностики.
     * quiet=true — успешные ответы (200) не пишутся (для массовых проб ID);
     * ошибки и не-200 пишутся всегда. Возвращает (HTTP-код, тело); код -1 при сетевой ошибке.
     */
    private fun restGet(
        scheme: String,
        port: Int,
        route: String,
        method: String = "GET",
        quiet: Boolean = false
    ): Pair<Int, String> {
        return try {
            val postBody: RequestBody? = if (method == "POST") ByteArray(0).toRequestBody() else null
            val request = Request.Builder()
                .url("$scheme://$host:$port/api/v2/$route")
                .method(method, postBody)
                .build()
            fastClient.newCall(request).execute().use { resp ->
                val code = resp.code
                val body = resp.body?.string() ?: ""
                if (!quiet || code !in 200..299) {
                    val bodyPart = if (body.isNotBlank()) " :: " + body.take(160).replace('\n', ' ') else ""
                    log("↳ HTTP $code ← $scheme:$port $method /api/v2/$route$bodyPart")
                }
                code to body
            }
        } catch (e: Exception) {
            log("!! REST $method $scheme:$port/$route: ${e.message}")
            -1 to ""
        }
    }

    /**
     * Умное выключение: разные прошивки принимают разные ключи.
     * 1) KEY_POWEROFF (2021+ часто только он); 2) KEY_POWER; 3) KEY_POWERTOGGLE;
     * 4) пара Press+Release (часть прошивок игнорирует одиночный Click).
     * После каждой попытки проверяем, отвечает ли ТВ (REST 8001): перестал — выключился.
     * Для старого протокола (55000) работает KEY_POWER.
     */
    fun powerOffSmart() {
        if (isLegacy()) {
            sendKey("KEY_POWER")
            return
        }
        Thread {
            for (k in listOf("KEY_POWEROFF", "KEY_POWER", "KEY_POWERTOGGLE")) {
                log("POWER: отправляю $k…")
                sendKey(k)
                Thread.sleep(2500)
                if (TvDiscovery.verify(host, 1200) == null) {
                    log("✔ ТВ выключился (сработал $k)")
                    onStatus("ТВ выключен — POWER снова включит его по сети")
                    return@Thread
                }
                log("$k не выключил ТВ — пробую следующий вариант")
            }
            // 4-я попытка: Press + Release (Click игнорируется частью прошивок)
            log("POWER: отправляю KEY_POWER (Press+Release)…")
            sendPressRelease("KEY_POWER")
            Thread.sleep(2500)
            if (TvDiscovery.verify(host, 1200) == null) {
                log("✔ ТВ выключился (сработал Press+Release)")
                onStatus("ТВ выключен — POWER снова включит его по сети")
                return@Thread
            }
            onStatus("ТВ не отреагировал на POWER — подробности в журнале")
        }.start()
    }

    /** Пара Cmd:Press → 150 мс → Cmd:Release (нужна части новых прошивок) */
    private fun sendPressRelease(key: String) {
        send(JSONObject().apply {
            put("method", "ms.remote.control")
            put("params", JSONObject().apply {
                put("Cmd", "Press")
                put("DataOfCmd", key)
                put("Option", "false")
                put("TypeOfRemote", "SendRemoteKey")
            })
        })
        Thread.sleep(150)
        send(JSONObject().apply {
            put("method", "ms.remote.control")
            put("params", JSONObject().apply {
                put("Cmd", "Release")
                put("DataOfCmd", key)
                put("Option", "false")
                put("TypeOfRemote", "SendRemoteKey")
            })
        })
    }

    fun close() {
        try {
            legacy?.close()
        } catch (_: Exception) {
        }
        legacy = null
        try {
            smart?.close()
        } catch (_: Exception) {
        }
        smart = null
        try {
            ws?.close(1000, "bye")
        } catch (_: Exception) {
        }
        ws = null
        connected = false
        transportAlive = false
    }

    private fun unsafeClient(): OkHttpClient {
        val ssl = SSLContext.getInstance("TLS")
        ssl.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        return OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .sslSocketFactory(ssl.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    companion object {
        /**
         * Известные app-ID (таблицы сообщества: tavicu/homebridge-samsung-tizen,
         * gist QE49Q85T, rs.ltd). Проверяются GET-запросом: 200 = установлено.
         * YouTube (111299001912) и Netflix убраны — по решению владельца пульта.
         */
        val KNOWN_APPS: List<Pair<String, String>> = listOf(
            "org.tizen.browser" to "Интернет",
            "3201907018784" to "Интернет (2020+)",
            "3202010022079" to "Интернет (2021+)",
            "3202405035480" to "Samsung TV Plus",
            "3201606009684" to "Spotify",
            "3202203026841" to "Twitch",
            "3202102023078" to "GeForce NOW",
            "3201702011851" to "Steam Link",
            "3202009021808" to "IPTV Smarters Pro",
            "3202102022877" to "SmartOne IPTV",
            "3201512006963" to "Plex",
            "3201606009872" to "Emby",
            "3201910019365" to "Prime Video",
            "3201512006785" to "Prime Video (стар. ID)",
            "3201807016597" to "Apple TV",
            "3201908019041" to "Apple Music",
            "3201710014874" to "Amazon Music",
            "3201608010191" to "Deezer",
            "121299000101" to "TuneIn",
            "3201806016390" to "DAZN",
            "3201901017640" to "Disney+",
            "3202009021709" to "Disney+ (2021+)",
            "3201710014981" to "Paramount+",
            "3201808016802" to "Pluto TV",
            "3201803015944" to "Discovery+",
            "3201710015016" to "SmartThings",
            "3201710015037" to "Gallery",
            "3201602007756" to "Red Bull TV"
        )

        /**
         * Известные ID приложений Orsay-эры (2011–2015). Проверяются через
         * GET /ws/apps/<id> (порт 8080): 200 = установлено, 404 = нет.
         * «Левые» ID из таблиц сообщества (старый формат 11–12 цифр) + часть
         * Tizen-эры на случай прошивок 2015 — лишние просто отсеются по 404.
         */
        val ORSAY_KNOWN_APPS: List<Pair<String, String>> = listOf(
            "1112990019" to "YouTube",
            "111299001912" to "YouTube",
            "11101200001" to "Netflix",
            "320160600" to "Netflix",
            "32015010026" to "Веб-браузер",
            "11091000000" to "Facebook",
            "121299000101" to "TuneIn Radio",
            "111399000741" to "The Weather Network",
            "121299000089" to "ITV Hub",
            "111299002148" to "All 4",
            "111012010001" to "VUDU",
            "111299000769" to "VOYO",
            "111399002034" to "RaiPlay",
            "3201512006963" to "Plex",
            "3201512006785" to "Prime Video",
            "3201601007625" to "Hulu",
            "3201606009684" to "Spotify",
            "3201606009872" to "Emby",
            "3201806016390" to "DAZN",
            "3201601007230" to "HBO"
        )

        /**
         * DIAL-имена: часть прошивок адресует приложения по имени пути
         * (/ws/apps/YouTube — подтверждено на Orsay H/J-сериях). Полного
         * перечня не существует, поэтому ищем по словарю популярных имён
         * эпохи Orsay (DIAL-реестр + captures Samsung 2012–2015), включая
         * российские сервисы. Лишние имена просто отсеются по 404.
         */
        val ORSAY_DIAL_NAMES: List<String> = listOf(
            // подтверждены на ТВ пользователя
            "YouTube", "Netflix",
            // мировые сервисы
            "AmazonInstantVideo", "PrimeVideo", "Hulu", "HuluPlus", "Vudu", "VUDU",
            "Plex", "Spotify", "TuneIn", "TuneInRadio", "iHeartRadio", "Deezer",
            "Napster", "Facebook", "Twitter", "Skype", "Vimeo", "Vevo", "TED",
            "Dailymotion", "Crunchyroll", "Funimation", "Zattoo", "SkyGo", "HBOGO",
            "HBO", "Showtime", "ShowtimeAnytime", "Starz", "StarzPlay", "SlingTV",
            "CBSAllAccess", "ESPN", "WWENetwork", "MUBI", "Mubi", "BritBox",
            "CuriosityStream", "RakutenTV", "Wuaki", "maxdome", "Maxdome", "Chili",
            "ARTE", "Arte", "RaiPlay", "RaiTV", "All4", "ITV", "ITVPlayer",
            "ITVHub", "BBCiPlayer", "Demand5", "My5", "UKTVPlay", "Tubi", "TubiTV",
            "PlutoTV", "Xumo", "Peacock", "DAZN", "EurosportPlayer", "RedBullTV",
            "Twitch", "Emby", "Jellyfin", "FuboTV", "Philo", "DiscoveryPlus",
            "ParamountPlus", "AppleTV", "AppleMusic", "DisneyPlus",
            // российские сервисы эпохи Smart Hub
            "IVI", "Ivi", "Okko", "MEGOGO", "Megogo", "Tvzavr", "tvzavr", "Zefir",
            "Kinopoisk", "Kion", "Wink", "Amediateka", "Premier", "Start", "Tvigle"
        )

        /**
         * Wake-on-LAN: пробуждение ТВ из режима сна.
         * На ТВ должно быть включено: Настройки -> Общие -> Сеть -> Пробуждение по сети.
         * Шлём 3 повтора на широковещательный адрес подсети и 255.255.255.255,
         * порты 9 и 7 — разные роутеры/ТВ принимают разные варианты.
         */
        fun wakeOnLan(mac: String, subnetBcast: String? = null): Boolean {
            return try {
                val macBytes = mac.split(":", "-")
                    .map { it.trim().toInt(16).toByte() }
                    .toByteArray()
                val payload = ByteArray(6 + 16 * 6)
                for (i in 0 until 6) payload[i] = 0xFF.toByte()
                for (i in 0 until 16) System.arraycopy(macBytes, 0, payload, 6 + i * 6, 6)
                val targets = mutableListOf("255.255.255.255")
                if (!subnetBcast.isNullOrBlank()) targets.add(subnetBcast)
                val socket = DatagramSocket()
                socket.broadcast = true
                for (rep in 0 until 3) {
                    for (t in targets) {
                        for (port in intArrayOf(9, 7)) {
                            socket.send(
                                DatagramPacket(payload, payload.size, InetAddress.getByName(t), port)
                            )
                            Thread.sleep(20)
                        }
                    }
                }
                socket.close()
                true
            } catch (e: Exception) {
                false
            }
        }
    }
}
