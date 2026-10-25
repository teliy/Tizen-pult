package com.teliy.tizenpult

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/**
 * v3.2: темы оформления пульта — пять вариантов.
 *
 *  - basic     — «как было»: Material 3, светлая/тёмная по настройкам системы,
 *                стандартные кнопки. Ничего не перекрашивается, кроме жёлтых
 *                иконок у медиа-кнопок (PLAY/PAUSE/STOP/REW/FF).
 *  - glass     — Glassmorphism, светлый: пастельный градиент с мягкими
 *                цветовыми пятнами (фон задаётся темой), полупрозрачные
 *                «стеклянные» карточки и кнопки с тонкой белой обводкой,
 *                при нажатии «стекло» плотнеет.
 *  - glass_dark— Glassmorphism, тёмный: глубокий тёмно-синий градиент,
 *                слабо светящиеся панели (стекло ~8% белого) со светлой
 *                обводкой, текст почти белый.
 *  - neu       — Неоморфизм, светлый: серо-голубой монохромный фон, «выпуклые»
 *                панели с двойной тенью (свет сверху-слева, тень снизу-справа),
 *                нажатие — «вдавливание». v3.2: текст заметно темнее и жирнее.
 *  - neu_dark  — Неоморфизм, тёмный: тёмный монохром, те же мягкие панели,
 *                светлые края сверху-слева и тёмные снизу-справа.
 *
 * Тема выбирается на экране «Настройки», хранится в prefs «theme».
 * Активити вызывает applyTheme() ДО setContentView() и styleRoot() ПОСЛЕ него.
 * Тени неоморфизма рисуются слоями (LayerDrawable) — работает от API 24.
 *
 * v3.2, медиа-иконки: глифы ► ▮▮ ■ ◄◄ ►► (текстовые символы, не эмодзи)
 * красятся в жёлтый — по просьбе пользователя, иначе иконка Play плохо видна.
 */
object ThemeEngine {

    const val BASIC = "basic"
    const val GLASS = "glass"
    const val GLASS_DARK = "glass_dark"
    const val NEU = "neu"
    const val NEU_DARK = "neu_dark"

    // ---------- Glassmorphism (светлая) ----------
    private val glassText = 0xFF232749.toInt()
    private val glassHint = 0xFF7A80A8.toInt()
    private val glassFillNormal = 0xB3FFFFFF.toInt()   // «стекло» 70% белого
    private val glassFillPressed = 0xE8FFFFFF.toInt()  // нажатое — плотнее
    private val glassFieldFill = 0x99FFFFFF.toInt()    // поля ввода
    private val glassCard = 0x96FFFFFF.toInt()         // карточки-секции
    private val glassStroke = 0xA6FFFFFF.toInt()       // белая обводка

    // ---------- Glassmorphism (тёмная) ----------
    private val glassTextDark = 0xFFE9EDFB.toInt()
    private val glassHintDark = 0xFF9AA3C7.toInt()
    private val glassFillNormalDark = 0x16FFFFFF.toInt()  // «стекло» ~9% белого
    private val glassFillPressedDark = 0x2EFFFFFF.toInt() // нажатое — плотнее
    private val glassFieldFillDark = 0x12FFFFFF.toInt()
    private val glassCardDark = 0x11FFFFFF.toInt()
    private val glassStrokeDark = 0x30FFFFFF.toInt()

    // ---------- Неоморфизм (светлый) ----------
    // v3.2: текст темнее (#454B60 → #262B3D) — жаловались, что плохо видно,
    // поверхность чуть светлее для контраста.
    private val neuText = 0xFF262B3D.toInt()
    private val neuHint = 0xFF848BA3.toInt()
    private val neuSurface = 0xFFF1F3F9.toInt()      // поверхность кнопки
    private val neuSurfaceDark = 0xFFE2E5EE.toInt()  // нажатая/вдавленная
    private val neuShadow = 0xFFC4CAD8.toInt()       // тень (нижний-правый край)
    private val neuHighlight = 0xFFFFFFFF.toInt()    // свет (верхний-левый край)

    // ---------- Неоморфизм (тёмный) ----------
    private val neuTextDark = 0xFFE5E8F2.toInt()
    private val neuHintDark = 0xFF8B92A8.toInt()
    private val neuSurfaceDk = 0xFF2D313C.toInt()     // поверхность кнопки
    private val neuSurfaceDkPressed = 0xFF262A33.toInt()
    private val neuShadowDk = 0xFF191B21.toInt()      // тень (нижний-правый край)
    private val neuHighlightDk = 0xFF3C4150.toInt()   // свет (верхний-левый край)

    // ---------- Жёлтые иконки медиа-кнопок (v3.2) ----------
    private val MEDIA_KEYS = setOf("KEY_PLAY", "KEY_PAUSE", "KEY_STOP", "KEY_REW", "KEY_FF")
    private val glyphYellowLight = 0xFFF0A000.toInt() // янтарно-жёлтый на светлых темах
    private val glyphYellowDark = 0xFFFFD54F.toInt()  // ярко-жёлтый на тёмных темах

    fun current(prefs: SharedPreferences): String =
        prefs.getString("theme", BASIC) ?: BASIC

    fun save(prefs: SharedPreferences, theme: String) {
        prefs.edit().putString("theme", theme).apply()
    }

    /** Ресурс темы для активити — вызывать ДО setContentView() */
    fun applyTheme(activity: Activity, prefs: SharedPreferences) {
        when (current(prefs)) {
            GLASS -> activity.setTheme(R.style.Theme_TizenPult_Glass)
            GLASS_DARK -> activity.setTheme(R.style.Theme_TizenPult_GlassDark)
            NEU -> activity.setTheme(R.style.Theme_TizenPult_Neu)
            NEU_DARK -> activity.setTheme(R.style.Theme_TizenPult_NeuDark)
        }
    }

    /**
     * Разовая стилизация всего дерева представлений активити — вызывать
     * ПОСЛЕ setContentView(). В базовой теме перекрашивается только
     * жёлтая иконка медиа-кнопок.
     */
    fun styleRoot(activity: Activity) {
        val prefs = activity.getSharedPreferences("tizenpult", Context.MODE_PRIVATE)
        activity.findViewById<View>(android.R.id.content)?.let { walk(it, current(prefs)) }
    }

    /** Кнопки, создаваемые кодом после styleRoot (ряд макросов) */
    fun styleDynamicButton(b: Button, prefs: SharedPreferences) {
        styleButton(b, current(prefs))
    }

    /**
     * Акцентный цвет темы — для рамок/выделений, рисуемых кодом
     * (например, обводка блоков в режиме расстановки пульта).
     */
    fun accent(prefs: SharedPreferences): Int = when (current(prefs)) {
        GLASS -> 0xFF5B6CF0.toInt()
        GLASS_DARK -> 0xFF8C9BFF.toInt()
        NEU -> 0xFF576078.toInt()
        NEU_DARK -> 0xFFA7B0FF.toInt()
        else -> 0xFF0B7A75.toInt() // базовая — фирменный бирюзовый
    }

    // ---------- обход дерева ----------

    private fun walk(v: View, theme: String) {
        when (v) {
            is Button -> styleButton(v, theme)
            is EditText -> if (theme != BASIC) styleEdit(v, theme)
            is TextView -> if (theme != BASIC) styleText(v, theme)
        }
        if (theme != BASIC) {
            when (v.id) {
                R.id.cardTv, R.id.cardTheme, R.id.cardApp -> styleCard(v, theme)
            }
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) walk(v.getChildAt(i), theme)
        }
    }

    // ---------- стилизация по типам ----------

    fun styleButton(b: Button, theme: String) {
        if (theme == BASIC) {
            // «Как было»: ничего не перекрашиваем и не меняем отступы —
            // только жёлтая иконка у медиа-кнопок
            accentMediaGlyph(b, theme)
            return
        }
        val d = b.resources.displayMetrics.density
        b.isAllCaps = false
        b.setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
        when (theme) {
            GLASS -> {
                b.background = glassButton(d, glassFillNormal, glassFillPressed)
                b.setTextColor(glassText)
            }
            GLASS_DARK -> {
                b.background = glassButton(d, glassFillNormalDark, glassFillPressedDark)
                b.setTextColor(glassTextDark)
            }
            NEU -> {
                b.background = neuButton(d, dark = false)
                b.setTextColor(neuText)
                b.setTypeface(null, Typeface.BOLD) // v3.2: текст плохо читался
            }
            NEU_DARK -> {
                b.background = neuButton(d, dark = true)
                b.setTextColor(neuTextDark)
                b.setTypeface(null, Typeface.BOLD)
            }
        }
        accentMediaGlyph(b, theme)
    }

    private fun styleEdit(e: EditText, theme: String) {
        val d = e.resources.displayMetrics.density
        e.setPadding((10 * d).toInt(), (9 * d).toInt(), (10 * d).toInt(), (9 * d).toInt())
        when (theme) {
            GLASS -> {
                e.background = glassPanel(d, 14f, glassFieldFill, glassStroke)
                e.setTextColor(glassText)
                e.setHintTextColor(glassHint)
            }
            GLASS_DARK -> {
                e.background = glassPanel(d, 14f, glassFieldFillDark, glassStrokeDark)
                e.setTextColor(glassTextDark)
                e.setHintTextColor(glassHintDark)
            }
            NEU -> {
                e.background = neuLayer(d, pressed = true, radiusDp = 14f, offDp = 3f,
                    surface = neuSurfaceDark, shadow = neuShadow, highlight = neuHighlight)
                e.setTextColor(neuText)
                e.setHintTextColor(neuHint)
            }
            NEU_DARK -> {
                e.background = neuLayer(d, pressed = true, radiusDp = 14f, offDp = 3f,
                    surface = neuSurfaceDkPressed, shadow = neuShadowDk, highlight = neuHighlightDk)
                e.setTextColor(neuTextDark)
                e.setHintTextColor(neuHintDark)
            }
        }
    }

    private fun styleText(t: TextView, theme: String) {
        when (theme) {
            GLASS -> t.setTextColor(glassText)
            GLASS_DARK -> t.setTextColor(glassTextDark)
            NEU -> t.setTextColor(neuText)
            NEU_DARK -> t.setTextColor(neuTextDark)
        }
    }

    private fun styleCard(v: View, theme: String) {
        val d = v.resources.displayMetrics.density
        v.background = when (theme) {
            GLASS -> glassPanel(d, 22f, glassCard, glassStroke)
            GLASS_DARK -> glassPanel(d, 22f, glassCardDark, glassStrokeDark)
            NEU -> neuLayer(d, pressed = false, radiusDp = 22f, offDp = 4.5f,
                surface = neuSurface, shadow = neuShadow, highlight = neuHighlight)
            NEU_DARK -> neuLayer(d, pressed = false, radiusDp = 22f, offDp = 4.5f,
                surface = neuSurfaceDk, shadow = neuShadowDk, highlight = neuHighlightDk)
            else -> return
        }
    }

    // ---------- жёлтые иконки медиа-кнопок (v3.2) ----------

    /** Жёлтый цвет иконки по теме: на тёмных — ярче */
    private fun glyphYellow(v: View, theme: String): Int = when (theme) {
        GLASS_DARK, NEU_DARK -> glyphYellowDark
        GLASS, NEU -> glyphYellowLight
        else -> // basic: следим за системной светлой/тёмной темой
            if (isNightMode(v)) glyphYellowDark else glyphYellowLight
    }

    private fun isNightMode(v: View): Boolean =
        (v.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /**
     * Перекрасить глиф медиа-кнопки в жёлтый: у «► PLAY» жёлтым становится
     * «►», подпись остаётся в цвете текста темы. Глифы в разметке — текстовые
     * символы (не эмодзи), поэтому цвет применяется всегда.
     */
    private fun accentMediaGlyph(b: Button, theme: String) {
        if ((b.tag as? String) !in MEDIA_KEYS) return
        val text = b.text?.toString() ?: return
        val sp = text.indexOf(' ')
        if (sp <= 0) return
        val s = SpannableString(text)
        s.setSpan(ForegroundColorSpan(glyphYellow(b, theme)), 0, sp, 0)
        b.text = s
    }

    // ---------- примитивы рисования ----------

    private fun roundRect(d: Float, radiusDp: Float, color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * d
            setColor(color)
        }

    /** Стеклянная панель: полупрозрачная заливка + тонкая обводка */
    private fun glassPanel(d: Float, radiusDp: Float, fill: Int, stroke: Int): GradientDrawable =
        roundRect(d, radiusDp, fill).apply {
            setStroke((1.2f * d).toInt().coerceAtLeast(1), stroke)
        }

    /** Стеклянная кнопка: обычное/нажатое стекло */
    private fun glassButton(d: Float, normal: Int, pressed: Int): StateListDrawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), glassPanel(d, 18f, pressed, glassStrokeOf(normal)))
            addState(intArrayOf(), glassPanel(d, 18f, normal, glassStrokeOf(normal)))
        }

    /** Обводка подбирается к варианту стекла (светлое — белая, тёмное — светло-серая) */
    private fun glassStrokeOf(fill: Int): Int =
        if (fill == glassFillNormal) glassStroke else glassStrokeDark

    /**
     * Неоморфная панель из трёх слоёв: тень, свет, поверхность.
     * pressed=false — «выпуклая» (тень снизу-справа, свет сверху-слева),
     * pressed=true  — «вдавленная» (тень сверху-слева, свет снизу-справа).
     */
    private fun neuLayer(
        d: Float, pressed: Boolean, radiusDp: Float, offDp: Float,
        surface: Int, shadow: Int, highlight: Int
    ): Drawable {
        val off = (offDp * d).toInt().coerceAtLeast(1)
        val ld = LayerDrawable(
            arrayOf<Drawable>(
                roundRect(d, radiusDp, shadow),
                roundRect(d, radiusDp, highlight),
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
    private fun neuButton(d: Float, dark: Boolean): StateListDrawable = StateListDrawable().apply {
        val surface = if (dark) neuSurfaceDk else neuSurface
        val surfaceP = if (dark) neuSurfaceDkPressed else neuSurfaceDark
        val shadow = if (dark) neuShadowDk else neuShadow
        val highlight = if (dark) neuHighlightDk else neuHighlight
        addState(
            intArrayOf(android.R.attr.state_pressed),
            neuLayer(d, pressed = true, radiusDp = 15f, offDp = 2.5f, surface = surfaceP,
                shadow = shadow, highlight = highlight)
        )
        addState(
            intArrayOf(),
            neuLayer(d, pressed = false, radiusDp = 15f, offDp = 3.5f, surface = surface,
                shadow = shadow, highlight = highlight)
        )
    }
}
