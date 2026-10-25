package com.teliy.tizenpult

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * v3.5: строки вне Activity (SamsungRemote, TvDiscovery, SmartViewRemote и др.).
 * Контекст приложения подставляется в onCreate обеих активити (L10n.init),
 * дальше s() доступен из любого фонового потока.
 *
 * v3.6: выбор языка в настройках. Пользовательский выбор хранится в prefs
 * «lang» («sys» — как у системы, иначе код en/ru/es/de/fr).
 *  - активити оборачиваются в attachBaseContext(L10n.wrap(...)) — их getString
 *    отдаёт строки выбранного языка;
 *  - s() (строки вне активити) берёт контекст через ctx() — тот же выбор,
 *    обёртка кэшируется, чтобы не создавать конфигурацию на каждый вызов.
 */
object L10n {
    const val PREFS = "tizenpult"
    const val KEY = "lang"

    @Volatile private var baseCtx: Context? = null
    @Volatile private var cachedLang: String? = null
    @Volatile private var cachedCtx: Context? = null

    fun init(c: Context) {
        baseCtx = c.applicationContext
    }

    /** Текущий выбор языка: «sys» или код (en/ru/es/de/fr) */
    fun lang(c: Context? = baseCtx): String =
        c?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getString(KEY, "sys") ?: "sys"

    /** Обёртка контекста с выбранной локалью — для attachBaseContext активити */
    fun wrap(c: Context): Context {
        val code = lang(c)
        if (code == "sys") return c
        val loc = Locale(code)
        Locale.setDefault(loc)
        val cfg = Configuration(c.resources.configuration)
        cfg.setLocale(loc)
        return c.createConfigurationContext(cfg)
    }

    /** Контекст для строк вне активити — с выбранной локалью (кэш по коду языка) */
    private fun ctx(): Context? {
        val base = baseCtx ?: return null
        val code = lang(base)
        val hit = cachedCtx
        if (hit != null && cachedLang == code) return hit
        val w = if (code == "sys") base else wrap(base)
        cachedLang = code
        cachedCtx = w
        return w
    }

    /** Ресурсная строка (с подстановкой аргументов, если они есть).
     *  Аргументы могут быть nullable (e.message и т.п.) — null печатается как «null»,
     *  как раньше делали шаблоны строк. */
    fun s(id: Int, vararg args: Any?): String {
        val c = ctx() ?: return ""
        if (args.isEmpty()) return c.getString(id)
        val safe: Array<out Any> = args.map { it ?: "null" }.toTypedArray()
        return c.getString(id, *safe)
    }
}
