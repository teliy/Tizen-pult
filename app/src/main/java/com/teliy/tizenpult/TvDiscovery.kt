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
 * 3. Скан подсети /24 всех локальных сетей телефона по порту 8001 (TCP connect);
 * 4. Каждый кандидат проверяется GET http://IP:8001/api/v2/ — JSON с блоком
 *    "device" есть только у ТВ Samsung (оттуда же берём имя и модель).
 *
 * Каждый этап пишет строку в отчёт (журнал приложения) — по нему видно,
 * на каком шаге и почему ТВ не нашёлся.
 */
object TvDiscovery {

    data class Result(
        val ip: String,
        val name: String,
        val model: String,
        val source: String   // «сохранённый адрес», «SSDP», «прошлый поиск», «скан сети»…
    )

    data class Report(
        val found: List<Result>,   // ТВ, ответившие прямо сейчас
        val log: List<String>      // подробности всех этапов — в журнал
    )

    class ScanOutcome {
        val open = mutableListOf<String>()   // порт 8001 открыт
        var refused = 0                       // хост жив, порт закрыт (быстрый «отказ»)
        var silent = 0                        // таймаут / недостижим
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

        // 1. Сохранённый адрес и найденные ранее — проверяем первыми
        if (!savedHost.isNullOrBlank() && candidates.add(savedHost)) {
            stage("кандидат: сохранённый адрес $savedHost")
        }
        for (ip in knownIps) {
            if (candidates.add(ip)) stage("кандидат: найден ранее $ip")
        }

        // 2. SSDP (до ~3 сек; включает MulticastLock — без него Android часто
        //    молча выбрасывает multicast-ответы на Wi-Fi)
        val t0 = System.currentTimeMillis()
        stage("SSDP-поиск (до 3 сек)…")
        val ssdp = ssdpIps(appContext)
        stage(
            "SSDP: ответов ${ssdp.size} за ${System.currentTimeMillis() - t0} мс" +
                if (ssdp.isEmpty()) "" else ": $ssdp"
        )
        for (ip in ssdp) candidates.add(ip)

        // 3. Скан подсети всех локальных сетей телефона (обычно одна — Wi-Fi)
        val nets = localIpv4s()
        if (nets.isEmpty()) {
            stage("локальный IPv4 не найден (Wi-Fi выключен? мобильный интернет?) — скан подсети пропущен")
        } else {
            stage("сети телефона: $nets")
            val donePrefixes = mutableSetOf<String>()
            for (local in nets) {
                val prefix = local.substringBeforeLast('.')
                if (!donePrefixes.add(prefix)) continue
                val t1 = System.currentTimeMillis()
                stage("скан $prefix.1–254, порт 8001…")
                val scan = scanSubnet(prefix)
                val openTxt = if (scan.open.isEmpty()) "нет" else scan.open.joinToString(", ")
                stage(
                    "скан $prefix.x за ${System.currentTimeMillis() - t1} мс: " +
                        "порт 8001 открыт → $openTxt, отказ → ${scan.refused}, тишина → ${scan.silent}"
                )
                for (ip in scan.open) candidates.add(ip)
            }
        }

        // 4. Проверка всех кандидатов REST-запросом /api/v2/
        val found = mutableListOf<Result>()
        for (ip in candidates) {
            onProgress("проверяю $ip…")
            val r = verify(ip, 1500)
            if (r != null) {
                val res = r.copy(
                    source = sourceOf(ip, savedHost, knownIps, ssdp)
                )
                found.add(res)
                stage("ТВ найден: ${res.name} — ${res.model} ($ip)")
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

    private fun sourceOf(
        ip: String,
        savedHost: String?,
        knownIps: List<String>,
        ssdp: List<String>
    ): String = when {
        ip == savedHost -> "сохранённый адрес"
        ssdp.contains(ip) -> "SSDP"
        knownIps.contains(ip) -> "найден ранее"
        else -> "скан сети"
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

    /** Параллельный TCP-скан /24 по порту 8001 (24 потока, 400 мс на адрес) */
    private fun scanSubnet(prefix: String): ScanOutcome {
        val out = ScanOutcome()
        val pool = Executors.newFixedThreadPool(24)
        try {
            val futures = (1..254).map { i ->
                pool.submit {
                    val ip = "$prefix.$i"
                    try {
                        Socket().use { s ->
                            s.connect(InetSocketAddress(ip, 8001), 400)
                        }
                        synchronized(out.open) { out.open.add(ip) }
                    } catch (_: ConnectException) {
                        synchronized(out) { out.refused++ }
                    } catch (_: SocketTimeoutException) {
                        synchronized(out) { out.silent++ }
                    } catch (_: Exception) {
                        synchronized(out) { out.silent++ }
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
