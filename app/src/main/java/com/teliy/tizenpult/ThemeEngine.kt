package com.teliy.tizenpult

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/**
 * v3.1: темы оформления пульта.
 *
 *  - basic — «как было»: Material 3, светлая/тёмная по настройкам системы,
 *    стандартные кнопки. Ничего не перекрашивается.
 *  - glass — Glassmorphism в светлой версии: пастельный градиент с мягкими
 *    цветовыми пятнами (фон задаётся темой), полупрозрачные «стеклянные»
 *    карточки и кнопки с тонкой белой обводкой, при нажатии «стекло» плотнеет.
 *  - neu   — Неоморфизм в светлой версии: серо-голубой монохромный фон,
 *    «выпуклые» панели с двойной тенью (свет сверху-слева, тень снизу-справа),
 *    нажатие — «вдавливание» (свет и тень меняются местами).
 *
 * Тема выбирается на экране «Настройки», хранится в prefs «theme».
 * Активити вызывает applyTheme() ДО setContentView() и styleRoot() ПОСЛЕ него.
 * Тени неоморфизма рисуются слоями (LayerDrawable) — работает от API 24.
 */
object ThemeEngine {

    const val BASIC = "basic"
    const val GLASS = "glass"
    const val NEU = "neu"

    // ---------- Glassmorphism (светлая) ----------
    private val glassText = Color.parseColor("#232749")
    private val glassHint = Color.parseColor("#7A80A8")
    private val glassFillNormal = 0xB3FFFFFF.toInt()   // «стекло» 70% белого
    private val glassFillPressed = 0xE8FFFFFF.toInt()  // нажатое — плотнее
    private val glassFieldFill = 0x99FFFFFF.toInt()    // поля ввода
    private val glassCard = 0x96FFFFFF.toInt()         // карточки-секции
    private val glassStroke = 0xA6FFFFFF.toInt()       // белая обводка

    // ---------- Неоморфизм (светлый) ----------
    private val neuText = Color.parseColor("#454B60")
    private val neuHint = Color.parseColor("#9AA0B4")
    private val neuSurface = Color.parseColor("#EEF1F7")      // поверхность кнопки
    private val neuSurfaceDark = Color.parseColor("#E2E5EC")  // нажатая/вдавленная
    private val neuShadow = Color.parseColor("#C6CCDA")       // тень (нижний-правый край)
    private val neuHighlight = Color.WHITE                    // свет (верхний-левый край)

    fun current(prefs: SharedPreferences): String =
        prefs.getString("theme", BASIC) ?: BASIC

    fun save(prefs: SharedPreferences, theme: String) {
        prefs.edit().putString("theme", theme).apply()
    }

    /** Ресурс темы для активити — вызывать ДО setContentView() */
    fun applyTheme(activity: Activity, prefs: SharedPreferences) {
        when (current(prefs)) {
            GLASS -> activity.setTheme(R.style.Theme_TizenPult_Glass)
            NEU -> activity.setTheme(R.style.Theme_TizenPult_Neu)
        }
    }

    /** Текущая тема — «стеклянная»? (для динамических кнопок вне дерева разметки) */
    fun isGlass(prefs: SharedPreferences): Boolean = current(prefs) == GLASS

    /**
     * Разовая стилизация всего дерева представлений активити — вызывать
     * ПОСЛЕ setContentView(). Для базовой темы ничего не делает.
     */
    fun styleRoot(activity: Activity) {
        val prefs = activity.getSharedPreferences("tizenpult", Context.MODE_PRIVATE)
        val glass = when (current(prefs)) {
            GLASS -> true
            NEU -> false
            else -> return
        }
        activity.findViewById<View>(android.R.id.content)?.let { walk(it, glass) }
    }

    /** Кнопки, создаваемые кодом после styleRoot (ряд макросов) */
    fun styleDynamicButton(b: Button, prefs: SharedPreferences) {
        when (current(prefs)) {
            GLASS -> styleButton(b, glass = true)
            NEU -> styleButton(b, glass = false)
        }
    }

    // ---------- обход дерева ----------

    private fun walk(v: View, glass: Boolean) {
        when (v) {
            is Button -> styleButton(v, glass)
            is EditText -> styleEdit(v, glass)
            is TextView -> styleText(v, glass)
        }
        when (v.id) {
            R.id.cardTv, R.id.cardTheme, R.id.cardApp -> styleCard(v, glass)
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) walk(v.getChildAt(i), glass)
        }
    }

    // ---------- стилизация по типам ----------

    fun styleButton(b: Button, glass: Boolean) {
        val d = b.resources.displayMetrics.density
        b.isAllCaps = false
        b.setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
        if (glass) {
            b.background = glassButton(d)
            b.setTextColor(glassText)
        } else {
            b.background = neuButton(d)
            b.setTextColor(neuText)
        }
    }

    private fun styleEdit(e: EditText, glass: Boolean) {
        val d = e.resources.displayMetrics.density
        e.setPadding((10 * d).toInt(), (9 * d).toInt(), (10 * d).toInt(), (9 * d).toInt())
        if (glass) {
            e.background = glassPanel(d, 14f, glassFieldFill)
            e.setTextColor(glassText)
            e.setHintTextColor(glassHint)
        } else {
            e.background = neuLayer(d, pressed = true, radiusDp = 14f, offDp = 3f, surface = neuSurfaceDark)
            e.setTextColor(neuText)
            e.setHintTextColor(neuHint)
        }
    }

    private fun styleText(t: TextView, glass: Boolean) {
        t.setTextColor(if (glass) glassText else neuText)
    }

    private fun styleCard(v: View, glass: Boolean) {
        val d = v.resources.displayMetrics.density
        v.background = if (glass) {
            glassPanel(d, 22f, glassCard)
        } else {
            neuLayer(d, pressed = false, radiusDp = 22f, offDp = 4.5f, surface = neuSurface)
        }
    }

    // ---------- примитивы рисования ----------

    private fun roundRect(d: Float, radiusDp: Float, color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * d
            setColor(color)
        }

    /** Стеклянная панель: полупрозрачная заливка + тонкая белая обводка */
    private fun glassPanel(d: Float, radiusDp: Float, fill: Int): GradientDrawable =
        roundRect(d, radiusDp, fill).apply {
            setStroke((1.2f * d).toInt().coerceAtLeast(1), glassStroke)
        }

    /** Стеклянная кнопка: обычное/нажатое стекло */
    private fun glassButton(d: Float): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), glassPanel(d, 18f, glassFillPressed))
        addState(intArrayOf(), glassPanel(d, 18f, glassFillNormal))
    }

    /**
     * Неоморфная панель из трёх слоёв: тень, свет, поверхность.
     * pressed=false — «выпуклая» (тень снизу-справа, свет сверху-слева),
     * pressed=true  — «вдавленная» (тень сверху-слева, свет снизу-справа).
     */
    private fun neuLayer(d: Float, pressed: Boolean, radiusDp: Float, offDp: Float, surface: Int): Drawable {
        val off = (offDp * d).toInt().coerceAtLeast(1)
        val ld = LayerDrawable(
            arrayOf<Drawable>(
                roundRect(d, radiusDp, neuShadow),
                roundRect(d, radiusDp, neuHighlight),
                roundRect(d, radiusDp, surface)
            )
        )
        if (pressed) {
            ld.setLayerInset(0, 0, 0, off, off)   // тень видна сверху-слева
            ld.setLayerInset(1, off, off, 0, 0)   // свет виден снизу-справа
        } else {
            ld.setLayerInset(0, off, off, 0, 0)   // тень видна снизу-справа
            ld.setLayerInset(1, 0, 0, off, off)   // свет виден сверху-слева
        }
        ld.setLayerInset(2, off, off, off, off)   // поверхность по центру
        return ld
    }

    /** Неоморфная кнопка: выпуклая, при нажатии — вдавливается */
    private fun neuButton(d: Float): StateListDrawable = StateListDrawable().apply {
        addState(
            intArrayOf(android.R.attr.state_pressed),
            neuLayer(d, pressed = true, radiusDp = 15f, offDp = 2.5f, surface = neuSurfaceDark)
        )
        addState(
            intArrayOf(),
            neuLayer(d, pressed = false, radiusDp = 15f, offDp = 3.5f, surface = neuSurface)
        )
    }
}
