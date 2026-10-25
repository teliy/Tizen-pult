package com.teliy.tizenpult

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Тачпад-поверхность пульта (v2.9).
 *
 * Два режима работы:
 *
 * 1) MOUSE — «настоящая» мышь для Tizen-ТВ (wss/ws): метод ProcessMouseDevice
 *    канала ms.remote.control. Формат И СЕМАНТИКА — по реверсу официальной
 *    библиотеки Samsung multiscreen (SmartThings/Smart View, класс RemoteControl):
 *      {"Cmd":"Move","Position":{"x":dX,"y":dY,"Time":"epoch-ms"},"TypeOfRemote":"ProcessMouseDevice"}
 *      {"Cmd":"LeftClick","TypeOfRemote":"ProcessMouseDevice"}
 *    ВАЖНО: Position.x/y — это ДЕЛЬТА сдвига курсора (в офиц. либе туда кладётся
 *    currentMoveDelta), а НЕ абсолютная позиция! v2.8 слала абсолютные координаты
 *    1920×1080 — ТВ трактовал их как сдвиг, курсор улетал за край и «замирал»
 *    (симптом теста: «курсор пропал, движения нет»). Палец двигает виртуальный
 *    курсор пульта, а в ТВ уходит разница с прошлым отправленным кадром.
 *
 * 2) ARROWS — для старых ТВ (Smart View 2.0 «smart», 55000 «legacy»): мыши в
 *    протоколе нет (в encrypted-API эталона только plugin RemoteControl — клавиши),
 *    поэтому свайп конвертируется в KEY_UP/DOWN/LEFT/RIGHT (шаг за порог,
 *    накопительно — длинный свайп = несколько нажатий), тап — KEY_ENTER.
 *
 * Move-кадры уходят тихо (без лога каждого кадра), иначе журнал забивается
 * за секунду — логика в SamsungRemote.sendMouseMove.
 */
class TouchpadView(
    context: Context,
    mouseMode: Boolean,
    private val onMove: (Int, Int) -> Unit,   // MOUSE: дельта сдвига курсора ТВ (dX,dY)
    private val onClick: () -> Unit,          // MOUSE: LeftClick / ARROWS: KEY_ENTER
    private val onArrow: (String) -> Unit     // ARROWS: KEY_UP/DOWN/LEFT/RIGHT
) : View(context) {

    /** Переключение режима на лету (тумблер «мышь ⇄ стрелки» в диалоге тачпада) */
    fun setMode(mouse: Boolean) {
        mouseMode = mouse
        lastSend = 0L
        invalidate()
    }

    companion object {
        /** Логическое пространство экрана ТВ */
        const val TV_W = 1920
        const val TV_H = 1080
        private const val MOVE_SEND_INTERVAL_MS = 40L  // не чаще ~25 кадров/с
        private const val ARROW_SEND_INTERVAL_MS = 60L // пауза между стрелками
        private const val CLICK_SLOP_DP = 18f          // тап — без сдвига пальца
    }

    // Виртуальный курсор (координаты ТВ, для отрисовки на тачпаде), старт — центр
    private var mouseMode = mouseMode
    private var curX = TV_W / 2f
    private var curY = TV_H / 2f
    // Виртуальная позиция на момент последней ОТПРАВКИ — от неё считается дельта
    private var sentX = TV_W / 2f
    private var sentY = TV_H / 2f

    // Состояние жеста
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var gestureDistance = 0f
    private var accX = 0f            // накопитель шага стрелок
    private var accY = 0f
    private var lastSend = 0L
    private var lastArrowAt = 0L
    private var lastDir = ""         // последняя стрелка (для подсветки)
    private var lastDirAt = 0L

    // Кисти
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF10161D.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2E3B49.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF233140.toInt(); strokeWidth = 2f
    }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4FC3F7.toInt(); strokeWidth = 4f; style = Paint.Style.STROKE
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4FC3F7.toInt() }
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF7E93A8.toInt() }
    private val hotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD54F.toInt() }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    /** Полный проход пальцем по ширине тачпада ≈ 1,4 ширины экрана ТВ */
    private fun gain(): Float = TV_W.toFloat() / width.coerceAtLeast(1) * 1.4f

    /** Порог одного «шага» стрелки — примерно 1/5 ширины тачпада */
    private fun arrowStep(): Float = width / 5f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = e.x; lastTouchY = e.y
                gestureDistance = 0f; accX = 0f; accY = 0f
                lastSend = 0L // новый жест не должен попадать под троттлинг прошлого
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - lastTouchX
                val dy = e.y - lastTouchY
                gestureDistance += hypot(dx.toDouble(), dy.toDouble()).toFloat()
                lastTouchX = e.x; lastTouchY = e.y
                if (mouseMode) moveMouse(dx, dy) else pushArrows(dx, dy)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (gestureDistance < dp(CLICK_SLOP_DP)) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onClick()
                } else if (mouseMode) {
                    // дожимаем конечную позицию, если троттлинг её не отправил
                    sendMoveNow(force = true)
                }
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun moveMouse(dx: Float, dy: Float) {
        val g = gain()
        curX = (curX + dx * g).coerceIn(0f, TV_W.toFloat())
        curY = (curY + dy * g).coerceIn(0f, TV_H.toFloat())
        sendMoveNow(force = false)
        invalidate()
    }

    /** Отправить накопленную ДЕЛЬТУ (разницу с прошлой отправкой) с троттлингом */
    private fun sendMoveNow(force: Boolean) {
        val dx = (curX - sentX).toInt()
        val dy = (curY - sentY).toInt()
        if (dx == 0 && dy == 0) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastSend < MOVE_SEND_INTERVAL_MS) return
        lastSend = now
        sentX += dx; sentY += dy // дробный остаток копится в (curX - sentX)
        onMove(dx, dy)
    }

    private fun pushArrows(dx: Float, dy: Float) {
        accX += dx; accY += dy
        val step = arrowStep()
        val now = SystemClock.elapsedRealtime()
        if (now - lastArrowAt < ARROW_SEND_INTERVAL_MS) return // копим, отправим на следующем событии
        while (abs(accX) >= step) {
            fireArrow(if (accX > 0) "KEY_RIGHT" else "KEY_LEFT")
            accX -= if (accX > 0) step else -step
            return
        }
        while (abs(accY) >= step) {
            fireArrow(if (accY > 0) "KEY_DOWN" else "KEY_UP")
            accY -= if (accY > 0) step else -step
            return
        }
    }

    private fun fireArrow(key: String) {
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        lastDir = key; lastDirAt = SystemClock.elapsedRealtime()
        lastArrowAt = lastDirAt
        onArrow(key)
        postInvalidateDelayed(400)
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat(); val h = height.toFloat()
        val r = dp(10f)
        c.drawRoundRect(0f, 0f, w, h, r, r, bgPaint)
        c.drawRoundRect(0f, 0f, w, h, r, r, borderPaint)

        if (mouseMode) {
            // перекрестие-подсказка
            c.drawLine(w / 2f, dp(8f), w / 2f, h - dp(8f), gridPaint)
            c.drawLine(dp(8f), h / 2f, w - dp(8f), h / 2f, gridPaint)
            // курсор в масштабе ТВ-координат
            val cx = curX / TV_W * w
            val cy = curY / TV_H * h
            val arm = dp(16f); val gap = dp(5f)
            c.drawCircle(cx, cy, dp(9f), cursorPaint)
            c.drawLine(cx - arm, cy, cx - gap, cy, cursorPaint)
            c.drawLine(cx + gap, cy, cx + arm, cy, cursorPaint)
            c.drawLine(cx, cy - arm, cx, cy - gap, cursorPaint)
            c.drawLine(cx, cy + gap, cx, cy + arm, cursorPaint)
            c.drawCircle(cx, cy, dp(2.2f), dotPaint)
            dimPaint.textAlign = Paint.Align.CENTER
            dimPaint.textSize = dp(12f)
            c.drawText("проведите пальцем — курсор ТВ; тап — клик", w / 2f, h - dp(10f), dimPaint)
        } else {
            dimPaint.textAlign = Paint.Align.CENTER
            dimPaint.textSize = dp(26f)
            val hot = SystemClock.elapsedRealtime() - lastDirAt < 400
            fun p(key: String) = if (hot && lastDir == key) hotPaint else dimPaint
            c.drawText("▲", w / 2f, dp(34f), p("KEY_UP"))
            c.drawText("▼", w / 2f, h - dp(12f), p("KEY_DOWN"))
            c.drawText("◀", dp(20f), h / 2f + dp(9f), p("KEY_LEFT"))
            c.drawText("▶", w - dp(20f), h / 2f + dp(9f), p("KEY_RIGHT"))
            c.drawText("тап = OK", w / 2f, h / 2f + dp(8f), dimPaint)
        }
    }
}
