package com.teliy.tizenpult

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Автопоиск телевизоров Samsung в локальной сети.
 *
 * Порядок:
 * 1. Кандидаты: сохранённый адрес + найденные ранее (история) + SSDP + скан подсети;
 * 2. SSDP-поиск (UPnP M-SEARCH на 239.255.255.250:1900) — до 3 сек;
 * 3. Скан подсети /24 всех локальных сетей телефона по портам 8001 (Tizen)
 *    и 55000 (старые модели 2011–2015, TCP connect);
 * 4. Каждый кандидат проверяется GET http://IP:8001/api/v2/ — JSON с блоком
 *    "device" есть только у ТВ Samsung с Tizen-API (оттуда же берём имя и модель).
 *    Кто не ответил — спрашивается по http://IP:7676/ms/1.0/ (старые Samsung 2011–2015)
 *    или признаётся старым по открытому порту 55000.
 *
 * Каждый этап пишет строку в отчёт (журнал приложения) — по нему видно,
 * на каком шаге и почему ТВ не нашёлся.
 */
object TvDiscovery {

    data class Result(
        val ip: String,
        val name: String,
        val model: String,
        val source: String,      // «сохранённый адрес», «SSDP», «прошлый поиск», «скан сети»…
        val legacy: Boolean = false // true — ТВ до Tizen (2011–2015), протокол TCP 55000
    )

    data class Report(
        val found: List<Result>,          // все найденные ТВ Samsung: и Tizen, и старые
        val log: List<String>             // подробности всех этапов — в журнал
    )

    class ScanOutcome {
        val open8001 = mutableListOf<String>()    // Tizen-API (2014+)
        val open55000 = mutableListOf<String>()   // старый протокол (2011–2015)
        var refused = 0                            // хост жив, оба порта закрыты
        var silent = 0                             // таймаут / недостижим
    }

    /**
     * Полный цикл поиска ВСЕХ ТВ Samsung в сети.
     * onProgress вызывается из фонового потока со строками статуса.
     */
    fun discoverAll(
        appContext: Context,
        savedHost: String?,
        knownIps: List<String>,
        onProgress: (String) -> Unit
    ): Report {
        val log = mutableListOf<String>()
        fun stage(msg: String) {
            log.add(msg)
            onProgress(msg)
        }

        val candidates = LinkedHashSet<String>()
        val legacyIps = mutableSetOf<String>()

        // 1. Сохранённый адрес и найденные ранее — проверяем первыми
        if (!savedHost.isNullOrBlank() && candidates.add(savedHost)) {
            stage(L10n.s(R.string.td_candidate_saved, savedHost))
        }
        for (ip in knownIps) {
            if (candidates.add(ip)) stage(L10n.s(R.string.td_candidate_known, ip))
        }

        // 2. SSDP (до ~3 сек; включает MulticastLock — без него Android часто
        //    молча выбрасывает multicast-ответы на Wi-Fi)
        val t0 = System.currentTimeMillis()
        stage(L10n.s(R.string.td_ssdp))
        val ssdp = ssdpIps(appContext)
        stage(
            L10n.s(R.string.td_ssdp_stat, ssdp.size, System.currentTimeMillis() - t0) +
                if (ssdp.isEmpty()) "" else ": $ssdp"
        )
        for (ip in ssdp) candidates.add(ip)

        // 3. Скан подсети всех локальных сетей телефона (обычно одна — Wi-Fi)
        val nets = localIpv4s()
        if (nets.isEmpty()) {
            stage(L10n.s(R.string.td_no_ipv4))
        } else {
            stage(L10n.s(R.string.td_phone_nets, nets))
            val donePrefixes = mutableSetOf<String>()
            for (local in nets) {
                val prefix = local.substringBeforeLast('.')
                if (!donePrefixes.add(prefix)) continue
                val t1 = System.currentTimeMillis()
                stage(L10n.s(R.string.td_scan_range, prefix))
                val scan = scanSubnet(prefix)
                legacyIps.addAll(scan.open55000)
                val o1 = if (scan.open8001.isEmpty()) L10n.s(R.string.td_no) else scan.open8001.joinToString(", ")
                val o5 = if (scan.open55000.isEmpty()) L10n.s(R.string.td_no) else scan.open55000.joinToString(", ")
                stage(
                    L10n.s(R.string.td_scan_stat, prefix, System.currentTimeMillis() - t1, o1, o5, scan.refused, scan.silent)
                )
                for (ip in scan.open8001) candidates.add(ip)
                for (ip in scan.open55000) candidates.add(ip)
            }
        }

        // 4. Проверка всех кандидатов: Tizen-API, затем старые Samsung
        val found = mutableListOf<Result>()
        for (ip in candidates) {
            onProgress(L10n.s(R.string.td_checking, ip))
            val r = verify(ip, 1500)
            if (r != null) {
                val res = r.copy(
                    source = sourceOf(ip, savedHost, knownIps, ssdp)
                )
                found.add(res)
                // 8001 отвечает и на старых Orsay (2014–2015) — различаем по REST-порту 8080
                val era = if (portOpen(ip, 8080, 400)) "Orsay 2014–2015" else "Tizen"
                stage(L10n.s(R.string.td_found, era, res.name, res.model, ip))
            } else {
                // Не отвечает по Tizen-API (2016+). Может, это старый Samsung
                // (2011–2015) — спросим его имя по http://IP:7676/ms/1.0/
                val dev = msDeviceInfo(ip)
                if (dev != null) {
                    val res = Result(
                        ip = ip,
                        name = dev.first.ifBlank { L10n.s(R.string.td_tv_samsung) },
                        model = dev.second,
                        source = sourceOf(ip, savedHost, knownIps, ssdp) + L10n.s(R.string.td_suffix_legacy),
                        legacy = true
                    )
                    found.add(res)
                    stage(L10n.s(R.string.td_old_stage, res.name, res.model, ip))
                } else if (legacyIps.contains(ip)) {
                    val res = Result(
                        ip = ip,
                        name = L10n.s(R.string.td_tv_samsung_old),
                        model = "",
                        source = sourceOf(ip, savedHost, knownIps, ssdp) + L10n.s(R.string.td_suffix_55000),
                        legacy = true
                    )
                    found.add(res)
                    stage(L10n.s(R.string.td_old_no_name, ip))
                }
            }
        }
        return Report(found, log)
    }

    /**
     * Быстрая проверка адреса: это Samsung ТВ?
     * GET http://IP:8001/api/v2/ отвечает JSON с блоком "device".
     */
    fun verify(ip: String, timeoutMs: Int): Result? {
        return try {
            val conn = URL("http://$ip:8001/api/v2/").openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val dev = JSONObject(body).optJSONObject("device") ?: return null
            var name = dev.optString("networkName")
            if (name.isBlank()) name = dev.optString("name")
            if (name.isBlank()) name = "[TV] Samsung"
            Result(ip, name, dev.optString("modelName"), source = "")
        } catch (_: Exception) {
            null
        }
    }

    /** Открыт ли TCP-порт (быстрая проверка для выбора протокола подключения) */
    fun portOpen(ip: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { it.connect(InetSocketAddress(ip, port), timeoutMs) }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun sourceOf(
        ip: String,
        savedHost: String?,
        knownIps: List<String>,
        ssdp: List<String>
    ): String = when {
        ip == savedHost -> L10n.s(R.string.td_saved_addr)
        ssdp.contains(ip) -> "SSDP"
        knownIps.contains(ip) -> L10n.s(R.string.td_known)
        else -> L10n.s(R.string.td_scan)
    }

    /**
     * Старые Samsung (до 2015) отдают описание по http://IP:7676/ms/1.0/ —
     * XML с FriendlyName и ModelName. Если ответили — это ТВ Samsung
     * со старым протоколом; подключим его через порт 55000.
     */
    private fun msDeviceInfo(ip: String): Pair<String, String>? {
        return try {
            val conn = URL("http://$ip:7676/ms/1.0/").openConnection() as HttpURLConnection
            conn.connectTimeout = 1200
            conn.readTimeout = 1200
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val friendly = Regex("<FriendlyName>(.*?)</FriendlyName>").find(body)?.groupValues?.get(1) ?: ""
            val model = Regex("<ModelName>(.*?)</ModelName>").find(body)?.groupValues?.get(1) ?: ""
            if (friendly.isBlank() && model.isBlank()) null else friendly.removePrefix("[") to model
        } catch (_: Exception) {
            null
        }
    }

    /**
     * SSDP M-SEARCH: 3 запроса (dial/MediaRenderer/ssdp:all), затем слушаем
     * одноадресные ответы до 2,6 сек. UDP-ошибки не прерывают приём.
     */
    @SuppressLint("WifiManagerPotentialLeak")
    private fun ssdpIps(appContext: Context): List<String> {
        val locations = LinkedHashSet<String>()

        // MulticastLock: на многих телефонах без него Wi-Fi-драйвер
        // не пропускает multicast/UDP-ответы
        var lock: WifiManager.MulticastLock? = null
        try {
            val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            lock = wifi.createMulticastLock("tizenpult-ssdp").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }

        var sock: DatagramSocket? = null
        try {
            sock = DatagramSocket(null)
            sock.reuseAddress = true
            sock.bind(InetSocketAddress(0))
            sock.soTimeout = 300
            val group = InetAddress.getByName("239.255.255.250")
            val sts = listOf(
                "urn:dial-multiscreen-org:service:dial:1",
                "urn:schemas-upnp-org:device:MediaRenderer:1",
                "ssdp:all"
            )
            for (st in sts) {
                try {
                    val msg = (
                        "M-SEARCH * HTTP/1.1\r\n" +
                            "HOST: 239.255.255.250:1900\r\n" +
                            "MAN: \"ssdp:discover\"\r\n" +
                            "MX: 2\r\n" +
                            "ST: $st\r\n\r\n"
                        ).toByteArray(Charsets.ISO_8859_1)
                    sock.send(DatagramPacket(msg, msg.size, group, 1900))
                } catch (_: Exception) {
                }
                try {
                    Thread.sleep(120)
                } catch (_: InterruptedException) {
                }
            }
            val deadline = System.currentTimeMillis() + 2600
            val buf = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    sock.receive(p)
                    val text = String(buf, 0, p.length, Charsets.ISO_8859_1)
                    if (text.contains("samsung", ignoreCase = true)) {
                        Regex("(?i)LOCATION\\s*:\\s*(\\S+)").find(text)
                            ?.groupValues?.get(1)
                            ?.let { locations.add(it) }
                    }
                } catch (_: SocketTimeoutException) {
                    // обычный таймаут ожидания — продолжаем до дедлайна
                } catch (_: IOException) {
                    break // сеть отвалилась — нет смысла крутиться
                }
            }
        } catch (_: Exception) {
            // не смогли даже создать сокет — вернём пустой список
        } finally {
            try {
                sock?.close()
            } catch (_: Exception) {
            }
            try {
                lock?.release()
            } catch (_: Exception) {
            }
        }

        // LOCATION вида http://192.168.1.225:7676/ms/... — достаём IP
        return locations.mapNotNull { loc ->
            Regex("http://([^:/]+)").find(loc)?.groupValues?.get(1)
        }.distinct()
    }

    /** Параллельный TCP-скан /24 по портам 8001 и 55000 (32 потока, 400 мс на адрес) */
    private fun scanSubnet(prefix: String): ScanOutcome {
        val out = ScanOutcome()
        val pool = Executors.newFixedThreadPool(32)
        try {
            val futures = (1..254).map { i ->
                pool.submit {
                    val ip = "$prefix.$i"
                    var refused1 = false
                    var silent1 = false
                    var refused2 = false
                    var silent2 = false
                    try {
                        Socket().use { s -> s.connect(InetSocketAddress(ip, 8001), 400) }
                        synchronized(out.open8001) { out.open8001.add(ip) }
                    } catch (_: ConnectException) {
                        refused1 = true
                    } catch (_: SocketTimeoutException) {
                        silent1 = true
                    } catch (_: Exception) {
                        silent1 = true
                    }
                    try {
                        Socket().use { s -> s.connect(InetSocketAddress(ip, 55000), 400) }
                        synchronized(out.open55000) { out.open55000.add(ip) }
                    } catch (_: ConnectException) {
                        refused2 = true
                    } catch (_: SocketTimeoutException) {
                        silent2 = true
                    } catch (_: Exception) {
                        silent2 = true
                    }
                    synchronized(out) {
                        if (refused1 && refused2) out.refused++
                        else if (silent1 && silent2) out.silent++
                    }
                }
            }
            for (f in futures) {
                try {
                    f.get(10, TimeUnit.SECONDS)
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        } finally {
            pool.shutdownNow()
        }
        return out
    }

    /** Все локальные IPv4 телефона (Wi-Fi/Ethernet первыми, VPN/модем в конце) */
    private fun localIpv4s(): List<String> {
        val out = mutableListOf<String>()
        try {
            val nifs = Collections.list(NetworkInterface.getNetworkInterfaces())
                .sortedByDescending { n ->
                    n.name.startsWith("wlan") || n.name.startsWith("eth")
                }
            for (nif in nifs) {
                if (!nif.isUp) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                        out.add(addr.hostAddress)
                        break
                    }
                }
            }
        } catch (_: Exception) {
        }
        return out.distinct()
    }
}
