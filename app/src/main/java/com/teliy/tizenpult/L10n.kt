package com.teliy.tizenpult

import android.content.Context

/**
 * v3.5: строки вне Activity (SamsungRemote, TvDiscovery, SmartViewRemote и др.).
 * Контекст приложения подставляется в onCreate обеих активити (L10n.init),
 * дальше s() доступен из любого фонового потока.
 */
object L10n {
    @Volatile private var appCtx: Context? = null

    fun init(c: Context) {
        appCtx = c.applicationContext
    }

    /** Ресурсная строка (с подстановкой аргументов, если они есть). */
    fun s(id: Int, vararg args: Any): String {
        val c = appCtx ?: return ""
        return c.getString(id, *args)
    }
}
