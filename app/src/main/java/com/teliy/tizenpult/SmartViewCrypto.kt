package com.teliy.tizenpult

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Криптография PIN-сопряжения Smart View 2.0 (Orsay, ТВ 2014-2015: H-серия и часть J).
 *
 * Эталон: xchwarze/samsung-tv-ws-api (authenticator.py, py3rijndael).
 * Порт проверен на эталонных векторах: расписание ключей и encryptBlock дают
 * побайтово тот же результат, что py3rijndael с num_rounds[16][16] = 3.
 *
 * Схема сопряжения:
 *  1) на ТВ открывается страница PIN (ws/apps/CloudPINPage), ТВ показывает 4 цифры;
 *  2) шаг 0-1: обмен ServerHello/ClientHello — DH (константы SDK) + AES-CBC ключом
 *     sha1(PIN)[:16] + WB_KEY-трансформация; проверка хэша = проверка PIN;
 *  3) из общего секрета: SKPrime -> SamyGO-трансформация -> токен сеанса (ctx);
 *  4) шаг 2: взаимные Ack -> session_id;
 *  5) команды: AES-128-ECB токеном, кадры socket.io v0.x (порт 8000).
 */
internal object SmartViewCrypto {
    private const val BLOCK = 16
    private const val PUBLIC_KEY =
        "2cb12bb2cbf7cec713c0fff7b59ae68a96784ae517f41d259a45d20556177c0ffe951ca60ec03a990c9412619d1bee30adc7773088c5721664cffcedacf6d251cb4b76e2fd7aef09b3ae9f9496ac8d94ed2b262eee37291c8b237e880cc7c021fb1be0881f3d0bffa4234d3b8e6a61530c00473ce169c025f47fcc001d9b8051"
    private const val PRIVATE_KEY =
        "2fd6334713816fae018cdee4656c5033a8d6b00e8eaea07b3624999242e96247112dcd019c4191f4643c3ce1605002b2e506e7f1d1ef8d9b8044e46d37c0d5263216a87cd783aa185490436c4a0cb2c524e15bc1bfeae703bcbc4b74a0540202e8d79cadaae85c6f9c218bc1107d1f5b4b9bd87160e782f4e436eeb17485ab4d"
    private const val WB_KEY = "abbb120c09e7114243d1fa0102163b27"
    private const val TRANS_KEY = "6c9474469ddf7578f3e5ad8a4c703d99"
    private const val PRIME =
        "b361eb0ab01c3439f2c16ffda7b05e3e320701ebee3e249123c3586765fd5bf6c1dfa88bb6bb5da3fde74737cd88b6a26c5ca31d81d18e3515533d08df619317063224cf0943a2f29a5fe60c1c31ddf28334ed76a6478a1122fb24c4a94c8711617ddfe90cf02e643cd82d4748d6d4a7ca2f47d88563aa2baf6482e124acd7dd"

    // SamyGO: Rijndael block=16, key=16, раундов 3 (вместо стандартных 10)
    private const val ROUNDS = 3
    private const val BC = 4                 // block/4
    private const val S1 = 1; private const val S2 = 2; private const val S3 = 3 // shifts[0]

    private val transRijndael: Array<IntArray> = buildTransRoundKeys()

    /** Расписание ключей TRANS_KEY (копия конструктора rijndael.py, rounds=3) */
    private fun buildTransRoundKeys(): Array<IntArray> {
        val key = hexBytes(TRANS_KEY)
        val ke = Array(ROUNDS + 1) { IntArray(BC) }
        val roundKeyCount = (ROUNDS + 1) * BC
        val kc = key.size / 4
        val tk = IntArray(kc)
        for (i in 0 until kc) {
            tk[i] = ((key[i * 4].toInt() and 0xFF) shl 24) or
                ((key[i * 4 + 1].toInt() and 0xFF) shl 16) or
                ((key[i * 4 + 2].toInt() and 0xFF) shl 8) or
                (key[i * 4 + 3].toInt() and 0xFF)
        }
        var t = 0
        var j = 0
        while (j < kc && t < roundKeyCount) {
            ke[t / BC][t % BC] = tk[j]
            j++; t++
        }
        var rconPointer = 0
        while (t < roundKeyCount) {
            val tt = tk[kc - 1]
            tk[0] = tk[0] xor
                (((RijndaelTables.S[(tt shr 16) and 0xFF] and 0xFF) shl 24) xor
                ((RijndaelTables.S[(tt shr 8) and 0xFF] and 0xFF) shl 16) xor
                ((RijndaelTables.S[tt and 0xFF] and 0xFF) shl 8) xor
                (RijndaelTables.S[(tt shr 24) and 0xFF] and 0xFF) xor
                ((RijndaelTables.RCON[rconPointer] and 0xFF) shl 24))
            rconPointer++
            for (i in 1 until kc) tk[i] = tk[i] xor tk[i - 1]
            j = 0
            while (j < kc && t < roundKeyCount) {
                ke[t / BC][t % BC] = tk[j]
                j++; t++
            }
        }
        return ke
    }

    /** encrypt одного 16-байтного блока — порт rijndael.py encrypt() */
    private fun rijndaelEncryptBlock(source: ByteArray): ByteArray {
        val t = IntArray(BC)
        val a = IntArray(BC)
        for (i in 0 until BC) {
            val word = ((source[i * 4].toInt() and 0xFF) shl 24) or
                ((source[i * 4 + 1].toInt() and 0xFF) shl 16) or
                ((source[i * 4 + 2].toInt() and 0xFF) shl 8) or
                (source[i * 4 + 3].toInt() and 0xFF)
            t[i] = word xor transRijndael[0][i]
        }
        for (r in 1 until ROUNDS) {
            for (i in 0 until BC) {
                a[i] = (RijndaelTables.T1[(t[i] shr 24) and 0xFF]
                    xor RijndaelTables.T2[(t[(i + S1) % BC] shr 16) and 0xFF]
                    xor RijndaelTables.T3[(t[(i + S2) % BC] shr 8) and 0xFF]
                    xor RijndaelTables.T4[t[(i + S3) % BC] and 0xFF]) xor transRijndael[r][i]
            }
            System.arraycopy(a, 0, t, 0, BC)
        }
        val result = ByteArray(16)
        for (i in 0 until BC) {
            val tt = transRijndael[ROUNDS][i]
            result[i * 4] = ((RijndaelTables.S[(t[i] shr 24) and 0xFF] xor (tt shr 24)) and 0xFF).toByte()
            result[i * 4 + 1] = ((RijndaelTables.S[(t[(i + S1) % BC] shr 16) and 0xFF] xor (tt shr 16)) and 0xFF).toByte()
            result[i * 4 + 2] = ((RijndaelTables.S[(t[(i + S2) % BC] shr 8) and 0xFF] xor (tt shr 8)) and 0xFF).toByte()
            result[i * 4 + 3] = ((RijndaelTables.S[t[(i + S3) % BC] and 0xFF] xor tt) and 0xFF).toByte()
        }
        return result
    }

    // ---------- вспомогательные ----------

    private fun hexBytes(hex: String): ByteArray {
        val n = hex.length / 2
        val out = ByteArray(n)
        for (i in 0 until n) {
            out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    internal fun bytesHexUpper(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) sb.append(String.format("%02X", x))
        return sb.toString()
    }

    private fun sha1(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-1")
        for (p in parts) md.update(p)
        return md.digest()
    }

    /** AES-CBC с нулевым IV, без паддинга (входы кратны 16 байтам) */
    private fun aesCbcZeroIv(key: ByteArray, data: ByteArray, encrypt: Boolean): ByteArray {
        val c = Cipher.getInstance("AES/CBC/NoPadding")
        c.init(
            if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(ByteArray(16))
        )
        return c.doFinal(data)
    }

    /** WB_KEY-трансформация: по 16 байт отдельным шифром (эквивалент ECB) */
    private fun wbTransform(data128: ByteArray, encrypt: Boolean): ByteArray {
        val key = hexBytes(WB_KEY)
        val out = ByteArrayOutputStream()
        var n = 0
        while (n < 128) {
            out.write(aesCbcZeroIv(key, data128.copyOfRange(n, n + 16), encrypt))
            n += 16
        }
        return out.toByteArray()
    }

    // ---------- этапы сопряжения ----------

    class ServerHello(val serverHelloHex: String, val dataHash: ByteArray, val aesKey: ByteArray)

    /** ServerHello из PIN — отправляется на шаге 1 */
    fun generateServerHello(userId: String, pin: String): ServerHello {
        val pinHash = sha1(pin.toByteArray(Charsets.UTF_8))
        val aesKey = pinHash.copyOfRange(0, 16)
        val encrypted = aesCbcZeroIv(aesKey, hexBytes(PUBLIC_KEY), true)
        val swapped = wbTransform(encrypted, true)
        val uid = userId.toByteArray(Charsets.UTF_8)

        val data = ByteArrayOutputStream()
        writeInt32(data, uid.size)
        data.write(uid)
        data.write(swapped)
        val dataBytes = data.toByteArray()
        val dataHash = sha1(dataBytes)

        val sh = ByteArrayOutputStream()
        sh.write(0x01); sh.write(0x02)
        sh.write(ByteArray(5))
        writeInt32(sh, uid.size + 132)
        sh.write(dataBytes)
        sh.write(ByteArray(5))
        return ServerHello(bytesHexUpper(sh.toByteArray()), dataHash, aesKey)
    }

    class ClientHelloResult(val ctxHex: String, val skPrime: ByteArray)

    /** Разбор ClientHello ТВ: null = неверный PIN */
    fun parseClientHello(
        clientHelloHex: String,
        dataHash: ByteArray,
        aesKey: ByteArray,
        userId: String
    ): ClientHelloResult? {
        val userIdPos = 15
        val userIdLenPos = 11
        val gxSize = 0x80
        val data = hexBytes(clientHelloHex)
        if (data.size < userIdPos + 132 + 20) return null

        val userIdLen = ((data[11].toInt() and 0xFF) shl 24) or
            ((data[12].toInt() and 0xFF) shl 16) or
            ((data[13].toInt() and 0xFF) shl 8) or
            (data[14].toInt() and 0xFF)

        val dest = ByteArrayOutputStream()
        dest.write(data, userIdLenPos, userIdLen + 132)
        dest.write(dataHash)

        val tvUserId = data.copyOfRange(userIdPos, userIdPos + userIdLen)
        val pEncWBGx = data.copyOfRange(userIdPos + userIdLen, userIdPos + userIdLen + gxSize)
        val pEncGx = wbTransform(pEncWBGx, false)
        val pGx = aesCbcZeroIv(aesKey, pEncGx, false)

        val bnPGx = BigInteger(1, pGx)
        val bnPrime = BigInteger(PRIME, 16)
        val bnPriv = BigInteger(PRIVATE_KEY, 16)
        val secret = stripLeadingZeros(bnPGx.modPow(bnPriv, bnPrime).toByteArray())

        val dataHash2 = data.copyOfRange(userIdPos + userIdLen + gxSize, userIdPos + userIdLen + gxSize + 20)
        if (!dataHash2.contentEquals(sha1(tvUserId, secret))) {
            return null // неверный PIN
        }

        val finalBuf = ByteArrayOutputStream()
        finalBuf.write(tvUserId)
        finalBuf.write(userId.toByteArray(Charsets.UTF_8))
        finalBuf.write(pGx)
        finalBuf.write(hexBytes(PUBLIC_KEY))
        finalBuf.write(secret)
        val skPrime = sha1(finalBuf.toByteArray())
        val skPrimeHash = sha1(skPrime, byteArrayOf(0))

        val ctx = rijndaelEncryptBlock(skPrimeHash.copyOfRange(0, 16))
        return ClientHelloResult(bytesHexUpper(ctx), skPrime)
    }

    /** ServerAckMsg для шага 2 */
    fun generateServerAcknowledge(skPrime: ByteArray): String {
        val h = sha1(skPrime, byteArrayOf(1))
        return "0103000000000000000014" + bytesHexUpper(h) + "0000000000"
    }

    /** Ожидаемый ClientAckMsg ТВ (для проверки ответа) */
    fun expectedClientAcknowledge(skPrime: ByteArray): String {
        val h = sha1(skPrime, byteArrayOf(2))
        return "0104000000000000000014" + bytesHexUpper(h) + "0000000000"
    }

    private fun writeInt32(out: ByteArrayOutputStream, v: Int) {
        out.write((v shr 24) and 0xFF)
        out.write((v shr 16) and 0xFF)
        out.write((v shr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    /** минимальное представление без ведущих нулей (аналог hex() в Python) */
    private fun stripLeadingZeros(b: ByteArray): ByteArray {
        var i = 0
        while (i < b.size - 1 && b[i].toInt() == 0) i++
        return b.copyOfRange(i, b.size)
    }
}
