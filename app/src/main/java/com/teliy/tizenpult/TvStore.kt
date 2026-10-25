package com.teliy.tizenpult

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * v3.1: общее хранилище истории найденных ТВ (prefs «knownTvs», JSON-массив).
 * Раньше жило приватно в MainActivity — теперь нужно и экрану «Настройки»,
 * который ищет и выбирает телевизоры, поэтому вынесено сюда.
 */
object TvStore {

    data class KnownTv(
        val ip: String,
        val name: String,
        val model: String,
        val lastSeen: Long,
        val legacy: Boolean = false
    )

    fun load(prefs: SharedPreferences): MutableList<KnownTv> = try {
        val arr = JSONArray(prefs.getString("knownTvs", "[]") ?: "[]")
        (0 until arr.length()).map {
            val x = arr.getJSONObject(it)
            KnownTv(
                x.getString("ip"),
                x.optString("name"),
                x.optString("model"),
                x.optLong("lastSeen"),
                x.optBoolean("legacy")
            )
        }.toMutableList()
    } catch (_: Exception) {
        mutableListOf()
    }

    fun save(prefs: SharedPreferences, list: List<KnownTv>) {
        val arr = JSONArray()
        for (k in list) {
            arr.put(
                JSONObject().apply {
                    put("ip", k.ip)
                    put("name", k.name)
                    put("model", k.model)
                    put("lastSeen", k.lastSeen)
                    put("legacy", k.legacy)
                }
            )
        }
        prefs.edit().putString("knownTvs", arr.toString()).apply()
    }

    /** Найденные сейчас ТВ — в историю (показываются в списках, даже когда выключены) */
    fun rememberFound(prefs: SharedPreferences, found: List<TvDiscovery.Result>) {
        if (found.isEmpty()) return
        val now = System.currentTimeMillis()
        val hist = load(prefs)
        for (r in found) {
            hist.removeAll { it.ip == r.ip }
            hist.add(0, KnownTv(r.ip, r.name, r.model, now, r.legacy))
        }
        save(prefs, hist.distinctBy { it.ip }.take(20))
    }
}
