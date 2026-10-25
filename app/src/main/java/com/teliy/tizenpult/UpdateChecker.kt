package com.teliy.tizenpult

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Проверка обновлений и скачивание APK прямо из приложения,
 * без захода на GitHub через браузер.
 *
 * Источник: https://api.github.com/repos/teliy/Tizen-pult/releases/latest
 * API публичный, токен не нужен (лимит 60 запросов/час с одного IP — для кнопки хватает).
 */
object UpdateChecker {

    private const val API_LATEST = "https://api.github.com/repos/teliy/Tizen-pult/releases/latest"
    private const val UA = "Tizen-Pult-updater"

    data class Release(
        val tag: String,        // например "v1.9"
        val version: String,    // "1.9"
        val apkUrl: String?,    // browser_download_url APK-ассета
        val apkSize: Long       // размер в байтах
    )

    /** Запрос последнего релиза GitHub (без черновиков и prerelease) */
    fun fetchLatest(): Release {
        val conn = URL(API_LATEST).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", UA)
        val code = conn.responseCode
        if (code != 200) {
            conn.disconnect()
            throw Exception("GitHub API: HTTP $code")
        }
        val body = conn.inputStream.bufferedReader().readText()
        conn.disconnect()

        val json = JSONObject(body)
        val tag = json.optString("tag_name", "")
        var apkUrl: String? = null
        var apkSize = 0L
        val assets = json.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name", "").endsWith(".apk", ignoreCase = true)) {
                    apkUrl = a.optString("browser_download_url", "")
                    apkSize = a.optLong("size", 0L)
                    break
                }
            }
        }
        return Release(tag = tag, version = tag.removePrefix("v"), apkUrl = apkUrl, apkSize = apkSize)
    }

    /**
     * Сравнение версий по сегментам: 1.9 > 1.8, 1.10 > 1.9.
     * Нечисловые хвосты ("1.9-beta") игнорируются.
     */
    fun isNewer(latest: String, current: String): Boolean {
        fun parse(v: String) = v.split('.').map {
            it.trim().takeWhile { c -> c.isDigit() }.toIntOrNull() ?: 0
        }
        val l = parse(latest)
        val c = parse(current)
        for (i in 0 until maxOf(l.size, c.size)) {
            val li = l.getOrElse(i) { 0 }
            val ci = c.getOrElse(i) { 0 }
            if (li != ci) return li > ci
        }
        return false
    }

    /**
     * Скачивание APK в dest с прогрессом (downloadedBytes, totalBytes).
     * Пишет во временный .part, по завершении переименовывает —
     * чтобы по обрывкам файлов нельзя было запустить битую установку.
     */
    fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", UA)
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            throw Exception("Скачивание: HTTP $code")
        }
        val total = conn.contentLengthLong
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".part")
        var done = 0L
        try {
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            if (total > 0 && done < total) throw Exception("Скачано $done из $total байт")
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
        } finally {
            if (tmp.exists()) tmp.delete()
            conn.disconnect()
        }
    }
}
