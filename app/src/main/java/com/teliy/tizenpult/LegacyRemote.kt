package com.teliy.tizenpult

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Клиент СТАРОГО протокола Samsung Smart TV (2011–2015, TCP-порт 55000) —
 * например UE48H6800 (2014). Работает в двух вариантах:
 *
 *  1. Обычный (plaintext) — формат эталона samsungctl (remote_legacy.py):
 *     - handshake: 00 00 00 [len] 00 <payload>, где payload = 64 00 +
 *       [len] 00 b64(описание) + [len] 00 b64(id) + [len] 00 b64(имя);
 *     - клавиша: 00 00 00 [len] 00 <payload>, payload = 00 00 00 + [len] 00 b64(KEY_…);
 *     - ответ ТВ: 3 байта заголовка (2-й и 3-й — длина имени ТВ, little-endian) +
 *       имя ТВ + 2 байта (длина ответа, little-endian) + ответ:
 *         64 00 01 00 — доступ разрешён;   64 00 00 00 — отказано;
 *         0A … — на ТВ ждут нажатия «Разрешить»;   65 … — пользователь отказал;
 *         00 00 00 00 — клавиша принята.
 *
 *  2. Зашифрованный (Smart View 2.0, модели H/J 2014–2015): те же пакеты, но каждый
 *     шифруется AES-128-CBC со статическими ключом (16 нулевых байт) и IV; ответ
 *     читается блоками по 16 байт, PKCS5-паддинг снимается вручную.
 */
class LegacyRemote(
    private val host: String,
    private val encrypted: Boolean,
    private val onStatus: (String) -> Unit,
    private val onLog: (String) -> Unit,
    private val onAuth: (Boolean) -> Unit,
    private val onTransport: (Boolean) -> Unit,
    private val onClosed: () -> Unit
) {
    @Volatile private var socket: Socket? = null
    @Volatile private var input: InputStream? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var authOk = false
    @Volatile private var waitingAuth = false
    @Volatile private var stopping = false

    /** Статические ключ и IV Smart View 2.0 (общеизвестные константы протокола) */
    private val aesKey = ByteArray(16)
    private val aesIv = hex("016790799ce4a7e27de95a7aa9d8d4e8")

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private fun log(msg: String) {
        onLog("${timeFmt.format(Date())}  $msg")
    }

    private fun hex(s: String): ByteArray {
        fun d(c: Char): Int = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            else -> c - 'A' + 10
        }
        return ByteArray(s.length / 2) { ((d(s[it * 2]) shl 4) or d(s[it * 2 + 1])).toByte() }
    }

    fun connect() {
        Thread {
            try {
                onStatus(if (encrypted) L10n.s(R.string.lr_connecting_aes) else L10n.s(R.string.lr_connecting))
                val s = Socket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(host, 55000), 5000)
                s.soTimeout = 10000
                socket = s
                input = s.getInputStream()
                output = s.getOutputStream()
                onTransport(true)
                log(L10n.s(R.string.lr_port_open) + if (encrypted) L10n.s(R.string.lr_aes_mode) else "")

                // Handshake как в samsungctl: payload = 64 00 + описание + id + имя
                var payload = byteArrayOf(0x64, 0x00)
                payload += chunk(L10n.s(R.string.lr_device_name))
                payload += chunk("tizenpult")
                payload += chunk("Tizen Pult")
                write(byteArrayOf(0x00, 0x00, 0x00) + chunkRaw(payload))
                log(L10n.s(R.string.lr_handshake_sent))

                Thread { readerLoop() }.start()
            } catch (e: Exception) {
                log(L10n.s(R.string.lr_connect_err, e.message))
                onStatus(L10n.s(R.string.lr_no_55000))
                onTransport(false)
                onAuth(false)
                close()
            }
        }.start()
    }

    fun sendKey(key: String) {
        if (output == null) {
            log(L10n.s(R.string.lr_no_conn_55000))
            return
        }
        if (!authOk) {
            log(L10n.s(R.string.lr_not_allowed, key))
            onStatus(L10n.s(R.string.lr_no_access_status))
            return
        }
        try {
            var payload = byteArrayOf(0x00, 0x00, 0x00)
            payload += chunk(key)
            write(byteArrayOf(0x00, 0x00, 0x00) + chunkRaw(payload))
            log("▶ $key (55000)")
        } catch (e: Exception) {
            log(L10n.s(R.string.lr_send_err, key, e.message))
        }
    }

    fun close() {
        stopping = true
        onTransport(false)
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        input = null
        output = null
    }

    // ---------- внутреннее ----------

    private fun write(packet: ByteArray) {
        val data = if (encrypted) encrypt(packet) else packet
        output?.let { o ->
            synchronized(o) {
                o.write(data)
                o.flush()
            }
        }
    }

    private fun encrypt(data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(aesIv))
        return c.doFinal(data)
    }

    private fun readerLoop() {
        val ins = input ?: return
        try {
            while (!stopping) {
                val resp: ByteArray? = try {
                    if (encrypted) readEncryptedFrame(ins) else readPlainFrame(ins)
                } catch (_: SocketTimeoutException) {
                    null
                }
                when {
                    resp != null && resp.isEmpty() -> break          // ТВ закрыл соединение (EOF)
                    resp != null -> handleResponse(resp)
                    authOk || waitingAuth -> {
                        // тишина между нажатиями или пока пользователь думает — ждём дальше
                    }
                    else -> {
                        log(L10n.s(R.string.lr_read_timeout))
                        break
                    }
                }
            }
            if (!stopping) {
                log(L10n.s(R.string.lr_closed))
                onClosed()
            }
        } catch (e: Exception) {
            if (!stopping) {
                log(L10n.s(R.string.lr_lost, e.message))
                onClosed()
            }
        }
    }

    private fun handleResponse(resp: ByteArray) {
        val granted = resp.size == 4 && resp[0] == 0x64.toByte() && resp[1] == 0x00.toByte() && resp[2] == 0x01.toByte()
        val denied = resp.size == 4 && resp[0] == 0x64.toByte() && resp[1] == 0x00.toByte() && resp[2] == 0x00.toByte()
        val keyOk = resp.size == 4 && resp[0].toInt() == 0 && resp[1].toInt() == 0 &&
            resp[2].toInt() == 0 && resp[3].toInt() == 0
        when {
            granted -> {
                waitingAuth = false
                authOk = true
                log(L10n.s(R.string.lr_granted))
                onStatus(L10n.s(R.string.connected_legacy))
                onAuth(true)
            }
            denied -> {
                authOk = false
                log(L10n.s(R.string.lr_denied_64))
                onStatus(L10n.s(R.string.lr_denied_64_status))
                onAuth(false)
            }
            resp[0] == 0x0A.toByte() -> {
                waitingAuth = true
                log(L10n.s(R.string.lr_wait_allow))
                onStatus(L10n.s(R.string.lr_allow_window))
            }
            resp[0] == 0x65.toByte() -> {
                waitingAuth = false
                authOk = false
                log(L10n.s(R.string.lr_denied_65))
                onStatus(L10n.s(R.string.lr_denied_65_status))
                onAuth(false)
            }
            keyOk -> log(L10n.s(R.string.lr_key_ok))
            else -> log(L10n.s(R.string.lr_resp_fmt, resp.joinToString(" ") { String.format("%02X", it) }))
        }
    }

    /** Кадр обычного протокола: 3 байта заголовка + имя ТВ + 2 байта + ответ */
    private fun readPlainFrame(ins: InputStream): ByteArray {
        val header = readFully(ins, 3) ?: return ByteArray(0)
        val nameLen = (header[1].toInt() and 0xFF) or ((header[2].toInt() and 0xFF) shl 8)
        if (nameLen > 0 && readFully(ins, nameLen) == null) return ByteArray(0)
        val lenB = readFully(ins, 2) ?: return ByteArray(0)
        val respLen = (lenB[0].toInt() and 0xFF) or ((lenB[1].toInt() and 0xFF) shl 8)
        if (respLen <= 0) return ByteArray(0)
        return readFully(ins, respLen) ?: ByteArray(0)
    }

    /**
     * Кадр AES-варианта: читаем поток блоками по 16 байт, расшифровываем на лету,
     * пока из расшифрованного не станет известна полная длина кадра.
     */
    private fun readEncryptedFrame(ins: InputStream): ByteArray {
        val c = Cipher.getInstance("AES/CBC/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(aesIv))
        val plain = ByteArrayOutputStream()
        var have = 0
        var nameLen = -1
        var total = -1
        while (total < 0 || have < ((total + 15) / 16) * 16) {
            val block = readFully(ins, 16) ?: return ByteArray(0)
            plain.write(c.update(block))
            have += 16
            val buf = plain.toByteArray()
            if (nameLen < 0 && buf.size >= 3) {
                nameLen = (buf[1].toInt() and 0xFF) or ((buf[2].toInt() and 0xFF) shl 8)
            }
            if (nameLen >= 0 && total < 0 && buf.size >= 3 + nameLen + 2) {
                val respLen = (buf[3 + nameLen].toInt() and 0xFF) or ((buf[4 + nameLen].toInt() and 0xFF) shl 8)
                total = 3 + nameLen + 2 + respLen
            }
            if (have > 8192) return ByteArray(0)
        }
        val full = plain.toByteArray()
        val pad = full[full.size - 1].toInt() and 0xFF
        val end = if (pad in 1..16) full.size - pad else full.size
        return full.copyOfRange(3 + nameLen + 2, end)
    }

    /** Точное чтение n байт; null — ТВ закрыл соединение (EOF); таймаут — исключение */
    private fun readFully(ins: InputStream, n: Int): ByteArray? {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = ins.read(out, off, n - off)
            if (r < 0) return null
            off += r
        }
        return out
    }

    /** Поле пакета старого протокола: [len] 00 b64(строка) */
    private fun chunk(s: String): ByteArray {
        val b64 = Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            .toByteArray(Charsets.ISO_8859_1)
        val out = ByteArray(2 + b64.size)
        out[0] = b64.size.toByte()
        System.arraycopy(b64, 0, out, 2, b64.size)
        return out
    }

    /** Обёртка готового payload: [len] 00 байты */
    private fun chunkRaw(b: ByteArray): ByteArray {
        val out = ByteArray(2 + b.size)
        out[0] = b.size.toByte()
        System.arraycopy(b, 0, out, 2, b.size)
        return out
    }
}
