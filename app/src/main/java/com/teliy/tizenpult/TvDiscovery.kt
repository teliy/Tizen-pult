package com.teliy.tizenpult

import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Автоматический поиск телевизора Samsung в локальной сети.
 *
 * Порядок поиска:
 * 1. Проверка сохранённого адреса (быстрый путь);
 * 2. SSDP-поиск (UPnP M-SEARCH на 239.255.255.250:1900) — ТВ Samsung отвечает
 *    на ST dial:1 и MediaRenderer; из ответов берём LOCATION и извлекаем IP;
 * 3. Скан подсети /24 по порту 8001 (TCP connect, параллельно, 350 мс на адрес);
 *
 * Кандидат проверяется запросом GET http://IP:8001/api/v2/ — там JSON с
 * блоком "device" (networkName, modelName) есть только у ТВ Samsung.
 */
object TvDiscovery {

    data class Result(val ip: String, val name: String, val model: String)

    /**
     * Полный цикл поиска. onProgress вызывается из фонового потока со
     * строками статуса для экрана. Возвращает null, если ТВ не найден.
     */
    fun discover(savedHost: String?, onProgress: (String) -> Unit): Result? {
        // 1. Сохранённый адрес — самый быстрый путь
        if (!savedHost.isNullOrBlank()) {
            onProgress("проверяю сохранённый адрес $savedHost…")
            val r = verify(savedHost, 1500)
            if (r != null) {
                onProgress("ТВ найден: $savedHost")
                return r
            }
            onProgress("сохранённый адрес недоступен, ищу ТВ в сети…")
        }

        // 2. SSDP (обычно 1–2 сек)
        onProgress("SSDP-поиск (до 3 сек)…")
        for (ip in ssdpIps()) {
            val r = verify(ip, 1500)
            if (r != null) {
                onProgress("ТВ найден (SSDP): ${r.name}")
                return r
            }
        }

        // 3. Скан подсети по порту 8001
        val local = localIp()
        if (local != null) {
            val prefix = local.substringBeforeLast('.')
            onProgress("сканирую сеть $prefix.1–254, порт 8001…")
            for (ip in scanSubnet(prefix)) {
                val r = verify(ip, 1200)
                if (r != null) {
                    onProgress("ТВ найден: ${r.name}")
                    return r
                }
            }
        }
        return null
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
            if (name.isBlank()) name = "[TV] $ip"
            Result(ip, name, dev.optString("modelName"))
        } catch (_: Exception) {
            null
        }
    }

    /** SSDP M-SEARCH: рассылаем 3 запроса, слушаем одноадресные ответы 3 секунды */
    private fun ssdpIps(): List<String> {
        val locations = LinkedHashSet<String>()
        try {
            DatagramSocket(null).use { sock ->
                sock.reuseAddress = true
                sock.bind(InetSocketAddress(0))
                sock.soTimeout = 400
                val group = InetSocketAddress("239.255.255.250", 1900)
                val sts = listOf(
                    "urn:dial-multiscreen-org:service:dial:1",
                    "urn:schemas-upnp-org:device:MediaRenderer:1",
                    "ssdp:all"
                )
                for (st in sts) {
                    val msg = (
                        "M-SEARCH * HTTP/1.1\r\n" +
                            "HOST: 239.255.255.250:1900\r\n" +
                            "MAN: \"ssdp:discover\"\r\n" +
                            "MX: 2\r\n" +
                            "ST: $st\r\n\r\n"
                        ).toByteArray(Charsets.ISO_8859_1)
                    try {
                        sock.send(DatagramPacket(msg, msg.size, group.address, 1900))
                    } catch (_: Exception) {
                    }
                    Thread.sleep(150)
                }
                val deadline = System.currentTimeMillis() + 3000
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
                    }
                }
            }
        } catch (_: Exception) {
        }
        // LOCATION вида http://192.168.1.225:7676/ms/... — достаём IP
        return locations.mapNotNull { loc ->
            Regex("http://([^:/]+)").find(loc)?.groupValues?.get(1)
        }.distinct()
    }

    /** Параллельный TCP-скан /24 по порту 8001 (24 потока, 350 мс на адрес) */
    private fun scanSubnet(prefix: String): List<String> {
        val hits = Collections.synchronizedList(mutableListOf<String>())
        val pool = Executors.newFixedThreadPool(24)
        try {
            val futures = (1..254).map { i ->
                pool.submit {
                    val ip = "$prefix.$i"
                    try {
                        Socket().use { s ->
                            s.connect(InetSocketAddress(ip, 8001), 350)
                        }
                        hits.add(ip)
                    } catch (_: Exception) {
                    }
                }
            }
            for (f in futures) {
                try {
                    f.get(8, TimeUnit.SECONDS)
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        } finally {
            pool.shutdownNow()
        }
        return hits
    }

    /** Локальный IPv4 телефона (для вычисления подсети) */
    private fun localIp(): String? {
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }
}
