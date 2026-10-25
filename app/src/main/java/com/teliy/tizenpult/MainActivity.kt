package com.teliy.tizenpult

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.DragEvent
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private var remote: SamsungRemote? = null

    private lateinit var statusView: TextView
    private lateinit var tvNowView: TextView

    private val logLines = ArrayDeque<String>()
    private var logDialogText: TextView? = null
    private var logDialogScroll: ScrollView? = null

    /** Идет ли сейчас подключение (защита от двойных тапов) */
    @Volatile private var connecting = false

    /** v3.1: тема, применённая при создании экрана (смена в настройках → recreate) */
    private var appliedTheme = ""

    /** v3.1: хост последнего подключения (смена ТВ в настройках → переподключение) */
    private var lastHostApplied: String? = null

    /** v3.3: первый onResume уже прошёл; без сохранённого ТВ при старте идёт автопоиск */
    private var startedOnce = false

    /** v3.3: режим расстановки блоков пульта (drag-n-drop) */
    private var editMode = false

    /** v3.3: подавление срабатывания спиннера ТВ при программной установке */
    private var suppressPicker = false

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        // v3.1: тема применяется до super.onCreate/setContentView
        prefs = getSharedPreferences("tizenpult", Context.MODE_PRIVATE)
        L10n.init(applicationContext) // v3.5: строки вне активити (SamsungRemote и др.)
        appliedTheme = ThemeEngine.current(prefs)
        ThemeEngine.applyTheme(this, prefs)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ThemeEngine.styleRoot(this)

        statusView = findViewById(R.id.statusView)
        tvNowView = findViewById(R.id.tvNowView)

        // v3.3: выбор ТВ — в шапке пульта; ⚙ — настройки (поиск ТВ там); ✎ — расстановка блоков
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btnEditLayout).setOnClickListener { toggleEditMode() }
        setupDragAndDrop() // v3.3: восстановить порядок блоков + приёмник перетаскивания
        findViewById<Button>(R.id.btnSendText).setOnClickListener { sendText() }
        findViewById<Button>(R.id.btnApps).setOnClickListener { openApps() }
        findViewById<Button>(R.id.btnUrl).setOnClickListener { showUrlDialog() }
        findViewById<Button>(R.id.btnMacros).setOnClickListener { showMacrosDialog() }
        findViewById<Button>(R.id.btnTouchpad).setOnClickListener { showTouchpad() }
        findViewById<Button>(R.id.btnLog).setOnClickListener { openLogDialog() }
        findViewById<TextView>(R.id.versionView).text = getString(R.string.version_fmt, currentVersionName())

        val btnPowerOn = findViewById<Button>(R.id.btnPowerOn)
        btnPowerOn.setOnClickListener { pressPower() }
        // Долгое нажатие — ручной ввод MAC (если REST выдал «Unknown» или закрыт)
        btnPowerOn.setOnLongClickListener {
            askMac()
            true
        }

        renderMacrosRow() // макросы per-ТВ — кнопки на главном экране

        // v3.3: без автопоиска при каждом старте — подключаемся к сохранённому ТВ
        // напрямую; полный поиск остаётся в ⚙ Настройках (и при первом запуске)
        val host = prefs.getString("host", null)
        if (host.isNullOrBlank()) findTv(manual = false) else connectToHost(host)
    }

    override fun onResume() {
        super.onResume()
        // v3.1: тема сменилась в настройках — пересобираем экран пульта
        val theme = ThemeEngine.current(prefs)
        if (theme != appliedTheme) {
            appliedTheme = theme
            recreate()
            return
        }
        // v3.1: ТВ сменился в настройках — подключаемся к новому при возврате
        val host = prefs.getString("host", null)
        if (startedOnce && !host.isNullOrBlank() && host != lastHostApplied) {
            connectToHost(host)
        }
        startedOnce = true
        refreshTvPicker(host) // v3.3: синхронизация списка ТВ в шапке после настроек
    }

    // ---------- v3.3: выбор ТВ в шапке пульта ----------

    /**
     * Выпадающий список ТВ в шапке: история (TvStore) + текущий хост.
     * Пустой список → пункт-подсказка, тап открывает «⚙ Настройки» (там поиск ТВ).
     * Выбор ТВ сразу сохраняется в prefs «host» и подключается.
     */
    private fun refreshTvPicker(selectIp: String?) {
        val spinner = findViewById<Spinner>(R.id.tvPicker)
        data class Opt(val label: String, val ip: String)
        val opts = mutableListOf<Opt>()
        val seen = mutableSetOf<String>()
        for (k in TvStore.load(prefs)) {
            if (seen.add(k.ip)) {
                val mark = if (selectIp == k.ip) "▸ " else ""
                val old = if (k.legacy) getString(R.string.m_suffix_old) else ""
                val nm = k.name.ifBlank { k.model.ifBlank { "Samsung" } }
                opts.add(Opt("$mark$nm — ${k.ip}$old", k.ip))
            }
        }
        if (selectIp != null && seen.add(selectIp)) {
            opts.add(0, Opt("▸ $selectIp", selectIp))
        }
        if (opts.isEmpty()) opts.add(Opt(getString(R.string.tv_none_settings), ""))

        suppressPicker = true
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, opts.map { it.label })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        val sel = opts.indexOfFirst { it.ip == selectIp }
        if (sel >= 0) spinner.setSelection(sel)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (suppressPicker) return
                val opt = opts.getOrNull(pos) ?: return
                if (opt.ip.isEmpty()) {
                    // пункт-подсказка — ведём в настройки, где живёт поиск ТВ
                    startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                    return
                }
                prefs.edit().putString("host", opt.ip).apply()
                connectToHost(opt.ip)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        // onItemSelected приходит после отрисовки — снимаем блокировку позже
        spinner.post { suppressPicker = false }
    }

    // ---------- v3.3: расстановка блоков пульта (drag-n-drop) ----------

    private val unitPrefix = "unit:"

    /** Дефолтный порядок блоков (в разметке); новые блоки добавляются в конец */
    private val defaultOrder = listOf(
        "apps", "url", "macros", "text", "power", "volch", "mute", "media",
        "digits", "dpad", "touchpad", "home", "hdmi", "ext", "log"
    )

    private fun unitKey(v: View): String? =
        (v.tag as? String)?.takeIf { it.startsWith(unitPrefix) }?.substring(unitPrefix.length)

    private fun forEachUnit(root: LinearLayout, block: (View) -> Unit) {
        for (i in 0 until root.childCount) {
            val c = root.getChildAt(i)
            if (unitKey(c) != null) block(c)
        }
    }

    private fun toggleEditMode() {
        if (editMode) exitEditMode() else enterEditMode()
    }

    private fun enterEditMode() {
        editMode = true
        val root = findViewById<LinearLayout>(R.id.remoteRoot)
        findViewById<TextView>(R.id.editHint).visibility = View.VISIBLE
        findViewById<Button>(R.id.btnEditLayout).text = "✓"
        forEachUnit(root) { u ->
            setInteractive(u, false)
            applyUnitChrome(u, on = true)
            u.setOnLongClickListener { startUnitDrag(u); true }
        }
        toast(getString(R.string.edit_mode_hint))
    }

    private fun exitEditMode() {
        editMode = false
        val root = findViewById<LinearLayout>(R.id.remoteRoot)
        findViewById<TextView>(R.id.editHint).visibility = View.GONE
        findViewById<Button>(R.id.btnEditLayout).text = "✎"
        forEachUnit(root) { u ->
            setInteractive(u, true)
            applyUnitChrome(u, on = false)
            u.setOnLongClickListener(null)
            u.isClickable = false
            u.isLongClickable = false
        }
        saveLayoutOrder(root)
        toast(getString(R.string.layout_saved))
    }

    /** В режиме расстановки гасим интерактивность содержимого блока — тапы не управляют ТВ */
    private fun setInteractive(v: View, on: Boolean) {
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) setInteractive(v.getChildAt(i), on)
        }
        when (v) {
            is EditText -> {
                v.isFocusable = on
                v.isFocusableInTouchMode = on
                v.isClickable = on
                v.isLongClickable = on
            }
            is Button -> {
                v.isClickable = on
                v.isLongClickable = on
            }
        }
    }

    /** Рамка режима расстановки вокруг блока (цвет — акцент темы) */
    private fun applyUnitChrome(u: View, on: Boolean) {
        val d = u.resources.displayMetrics.density
        if (!on) {
            u.background = null
            u.setPadding(0, 0, 0, 0)
            return
        }
        val color = ThemeEngine.accent(prefs)
        u.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8f * d
            setStroke((1.4f * d).toInt().coerceAtLeast(1), color)
            setColor(0x14000000 or (color and 0x00FFFFFF))
        }
        u.setPadding((6 * d).toInt(), (2 * d).toInt(), (6 * d).toInt(), (2 * d).toInt())
    }

    /** Долгий тап по блоку в режиме расстановки — начинаем перетаскивание */
    private fun startUnitDrag(u: View) {
        if (unitKey(u) == null) return
        u.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val data = ClipData.newPlainText("unit", unitKey(u))
        u.startDragAndDrop(data, View.DragShadowBuilder(u), u, 0)
    }

    /** Восстановить сохранённый порядок блоков и повесить приёмник перетаскивания */
    private fun setupDragAndDrop() {
        val root = findViewById<LinearLayout>(R.id.remoteRoot)
        restoreLayoutOrder(root)
        root.setOnDragListener { _, ev ->
            when (ev.action) {
                DragEvent.ACTION_DRAG_STARTED ->
                    ev.clipDescription?.label?.toString() == "unit" && ev.localState is View
                DragEvent.ACTION_DRAG_LOCATION -> {
                    moveDragged(root, ev)
                    autoScrollWhileDrag(root, ev)
                    true
                }
                DragEvent.ACTION_DROP -> true
                DragEvent.ACTION_DRAG_ENDED -> {
                    if (editMode) saveLayoutOrder(root)
                    true
                }
                else -> true
            }
        }
    }

    /** Живая перестановка: блок следует за пальцем между позициями других блоков */
    private fun moveDragged(root: LinearLayout, ev: DragEvent) {
        val dragged = ev.localState as? View ?: return
        val units = (0 until root.childCount).map { root.getChildAt(it) }
            .filter { unitKey(it) != null }
        val ci = units.indexOf(dragged)
        if (ci < 0) return
        // сколько блоков (без перетаскиваемого) выше пальца — это позиция вставки
        var ins = 0
        for (u in units) {
            if (u === dragged) continue
            if (ev.y > u.top + u.height / 2f) ins++
        }
        if (ins == ci) return // позиция не меняется
        root.removeView(dragged)
        val remaining = units.filter { it !== dragged }
        if (ins >= remaining.size) {
            val last = remaining.lastOrNull()
            root.addView(dragged, if (last == null) 0 else root.indexOfChild(last) + 1)
        } else {
            root.addView(dragged, root.indexOfChild(remaining[ins]))
        }
    }

    /** Автопрокрутка экрана, когда палец с блоком у верхнего/нижнего края */
    private fun autoScrollWhileDrag(root: LinearLayout, ev: DragEvent) {
        val sv = root.parent as? ScrollView ?: return
        val d = resources.displayMetrics.density
        val edge = 48 * d
        val rel = ev.y - sv.scrollY
        val step = (6 * d).toInt()
        when {
            rel < edge -> sv.scrollBy(0, -step)
            rel > sv.height - edge -> sv.scrollBy(0, step)
        }
    }

    /** Порядок блоков — в prefs «layout_order» */
    private fun saveLayoutOrder(root: LinearLayout) {
        val order = (0 until root.childCount).mapNotNull { unitKey(root.getChildAt(it)) }
        prefs.edit().putString("layout_order", order.joinToString(",")).apply()
    }

    /** Расставить блоки по сохранённому порядку; новые блоки — в дефолтные позиции */
    private fun restoreLayoutOrder(root: LinearLayout) {
        val saved = prefs.getString("layout_order", null) ?: return
        val savedKeys = saved.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (savedKeys.isEmpty()) return
        val byKey = HashMap<String, View>()
        forEachUnit(root) { byKey[unitKey(it)!!] = it }
        val finalKeys = savedKeys.filter { byKey.containsKey(it) } +
            defaultOrder.filter { it in byKey && it !in savedKeys }
        val current = (0 until root.childCount).mapNotNull { unitKey(root.getChildAt(it)) }
        if (finalKeys == current) return
        for (v in byKey.values) root.removeView(v)
        val footer = findViewById<View>(R.id.footerHint)
        var idx = root.indexOfChild(footer).let { if (it < 0) root.childCount else it }
        for (k in finalKeys) {
            byKey[k]?.let { root.addView(it, idx); idx++ }
        }
    }

    /** Кнопки с андроид:tag="KEY_..." в разметке обрабатываются здесь */
    fun onKeyClick(v: View) {
        val key = v.tag as? String ?: return
        sendOrToast { sendKey(key) }
    }

    private fun sendOrToast(action: SamsungRemote.() -> Unit) {
        val r = remote
        if (r == null) {
            toast(getString(R.string.need_tv_first))
            return
        }
        try {
            r.action()
        } catch (e: Exception) {
            toast(getString(R.string.m_error_fmt, e.message))
            appendLog(getString(R.string.m_send_err_log, e.message))
        }
    }

    /** v3.0: строка «На ТВ: …» — текущий канал/приложение (пустая строка — скрыть) */
    private fun showTvNow(s: String?) {
        if (s.isNullOrBlank()) {
            tvNowView.visibility = View.GONE
            tvNowView.text = ""
        } else {
            tvNowView.visibility = View.VISIBLE
            tvNowView.text = getString(R.string.on_tv_fmt, s)
        }
    }

    /**
     * Автопоиск ТВ: сохранённый IP + прошлые находки + SSDP + скан всех подсетей.
     * Найденное попадает в общую историю (TvStore) и в список на экране «Настройки».
     * Один найденный (или сохранённый среди найденных) ТВ подключается сразу;
     * если найдено несколько и сохранённого среди них нет — выбор в «Настройках».
     * Если ни одного и известен MAC — пробуждение по сети и повторная проверка.
     * Каждый этап пишется в журнал — по нему можно понять, почему ТВ не нашёлся.
     */
    private fun findTv(manual: Boolean) {
        statusView.text = if (manual) getString(R.string.search_tv) else getString(R.string.autosearch_tv)
        Thread {
            var report = TvDiscovery.discoverAll(
                applicationContext,
                prefs.getString("host", null),
                TvStore.load(prefs).map { it.ip }
            ) { p ->
                runOnUiThread { statusView.text = "🔎 $p" }
            }

            // Подробности всех этапов — в журнал (для диагностики в чат)
            val reportToLog = report
            runOnUiThread {
                appendLog(getString(R.string.m_find_log, if (manual) getString(R.string.m_find_manual) else getString(R.string.m_find_auto)))
                for (line in reportToLog.log) appendLog(line)
            }

            // Ничего не нашли — возможно, ТВ спит: пробуждаем и проверяем ещё раз
            if (report.found.isEmpty()) {
                val mac = prefs.getString("mac", null)
                val saved = prefs.getString("host", null)
                if (!mac.isNullOrBlank() && !saved.isNullOrBlank()) {
                    runOnUiThread { statusView.text = getString(R.string.wol_sending) }
                    SamsungRemote.wakeOnLan(mac, saved.substringBeforeLast('.') + ".255")
                    Thread.sleep(5000)
                    val r = TvDiscovery.verify(saved, 2000)
                    if (r != null) {
                        report = TvDiscovery.Report(
                            listOf(r.copy(source = getString(R.string.m_wol_source))),
                            listOf(getString(R.string.m_wol_woke, saved))
                        )
                        runOnUiThread { appendLog(report.log.first()) }
                    }
                }
            }

            TvStore.rememberFound(prefs, report.found)

            runOnUiThread {
                val saved = prefs.getString("host", null)
                when {
                    report.found.isEmpty() ->
                        statusView.text =
                            getString(R.string.not_found_settings)
                    report.found.any { it.ip == saved } ->
                        connectToHost(saved!!)
                    report.found.size == 1 -> {
                        val tv = report.found[0]
                        prefs.edit().putString("host", tv.ip).apply()
                        connectToHost(tv.ip)
                    }
                    else ->
                        statusView.text =
                            getString(R.string.m_found_n_settings, report.found.size)
                }
            }
        }.start()
    }

    /**
     * Подключение с автоопределением протокола:
     *  - wss:8002 с токеном — Tizen 2018+ (UE43AU9000 и другие новые);
     *  - ws:8001 без TLS — Tizen 2016–2017 (с пробой полноценности: старые ТВ
     *    подключение принимают, но управление отклоняют);
     *  - legacy TCP:55000 — Smart TV 2011–2015, обычный и AES (пробуется всегда:
     *    если порт закрыт, отказ приходит мгновенно);
     *  - smart — Smart View 2.0 (8080/8000): Orsay H-серия 2014, часть J 2015
     *    (UE48H6800 и т.п.) — при первом подключении на ТВ покажут PIN.
     * Рабочий протокол запоминается в prefs «proto:<ip>».
     */
    private fun connectToHost(host: String) {
        if (connecting) {
            runOnUiThread { statusView.text = getString(R.string.already_connecting) }
            return
        }
        connecting = true
        lastHostApplied = host // v3.1: onResume после настроек не переподключается зря
        Thread {
            try {
                runOnUiThread {
                    statusView.text = getString(R.string.m_connecting, host)
                    showTvNow(null) // прежний «На ТВ: …» больше не актуален
                    if (logLines.isNotEmpty()) appendLog(getString(R.string.new_session))
                }

                // 1. Какие порты вообще открыты на этом ТВ
                val p8002 = TvDiscovery.portOpen(host, 8002, 1500)
                val p8001 = TvDiscovery.portOpen(host, 8001, 1500)
                val p55000 = TvDiscovery.portOpen(host, 55000, 1500)
                // Smart View 2.0 ищем только у ТВ без Tizen-TLS (экономим время на новых)
                var p8080 = false
                var p8000 = false
                if (!p8002) {
                    p8080 = TvDiscovery.portOpen(host, 8080, 1200)
                    p8000 = TvDiscovery.portOpen(host, 8000, 1200)
                }
                runOnUiThread {
                    appendLog(getString(R.string.m_ports_log, p8002, p8001, p55000, p8080, p8000))
                }

                // 2. Порядок проб: сохранённый протокол — первым, затем остальные
                val caps = mutableListOf<String>()
                if (p8002) caps.add("wss")
                if (p8001) caps.add("ws")
                caps.add("legacy")
                caps.add("legacy-aes")
                if (p8080 || p8000) caps.add("smart")
                val saved = prefs.getString("proto:$host", null)
                if (!saved.isNullOrBlank() && caps.remove(saved)) caps.add(0, saved)

                // 3. Пробуем по очереди, пока какой-нибудь не подключится
                for (proto in caps) {
                    if (tryProto(host, proto)) {
                        prefs.edit().putString("proto:$host", proto).apply()
                        runOnUiThread {
                            appendLog(getString(R.string.m_proto_saved, proto, host))
                        }
                        learnMac(host) // MAC для Wake-on-LAN — на будущее
                        return@Thread
                    }
                }
                runOnUiThread {
                    statusView.text = getString(R.string.connect_fail)
                    appendLog(getString(R.string.m_no_proto))
                }
            } finally {
                connecting = false
            }
        }.start()
    }

    /** Одна попытка: создаем клиент и ждём подтверждения сеанса (до 8/30/150 с) */
    private fun tryProto(host: String, proto: String): Boolean {
        runOnUiThread {
            appendLog(getString(R.string.m_try_proto, proto))
            statusView.text = getString(R.string.m_connecting_proto, host, proto)
        }
        val r = SamsungRemote(
            host = host,
            proto = proto,
            token = prefs.getString("token:$host", null),
            smartAuth = prefs.getString("sv:$host", null),
            deviceId = deviceUuid(),
            onStatus = { s -> runOnUiThread { statusView.text = s } },
            onToken = { t -> prefs.edit().putString("token:$host", t).apply() },
            onApps = { apps, catalog -> runOnUiThread { showApps(apps, catalog) } },
            onLog = { s -> runOnUiThread { appendLog(s) } },
            onSmartAuth = { auth -> prefs.edit().putString("sv:$host", auth).apply() },
            onPin = { retry, submit -> showPinDialog(retry, submit) },
            onTvNow = { s -> runOnUiThread { showTvNow(s) } }
        )
        remote?.close()
        remote = r
        r.connect()

        // smart: сопряжение с вводом PIN занимает время — даём до 5 минут
        val deadAfter = if (proto == "smart") 300_000L else 8_000L
        val aliveAfter = if (proto == "smart") 330_000L else 30_000L
        val t0 = System.currentTimeMillis()
        while (true) {
            // rejected — первым: у ws://8001 connect успешен, но пробы API ещё могут отклониться
            if (r.isRejected()) {
                // ws://8001 отклонён ТВ — убираем его из сохранённых, чтобы не пробовать первым
                if (prefs.getString("proto:$host", null) == "ws") {
                    prefs.edit().remove("proto:$host").apply()
                }
                return false
            }
            if (r.isConnected()) return true
            val elapsed = System.currentTimeMillis() - t0
            // 30 с — если транспорт жив (например, ТВ ждёт «Разрешить» на экране),
            // 8 с — если транспорт так и не поднялся
            if (r.isTransportAlive()) {
                if (elapsed > aliveAfter) break
            } else if (elapsed > deadAfter) {
                break
            }
            Thread.sleep(250)
        }
        val ok = r.isConnected()
        if (!ok) r.close()
        return ok
    }

    /** Стабильный UUID устройства для сопряжения Smart View 2.0 (prefs «svdevid») */
    private fun deviceUuid(): String {
        var id = prefs.getString("svdevid", null)
        if (id.isNullOrBlank()) {
            id = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("svdevid", id).apply()
        }
        return id
    }

    /** Диалог ввода PIN с экрана ТВ (сопряжение Smart View 2.0) */
    private fun showPinDialog(retry: Boolean, submit: (String?) -> Unit) {
        runOnUiThread {
            val input = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                hint = "1234"
            }
            AlertDialog.Builder(this)
                .setTitle(if (retry) getString(R.string.pin_retry) else getString(R.string.pin_title))
                .setMessage(getString(R.string.pin_message))
                .setView(input)
                .setPositiveButton("OK") { _, _ -> submit(input.text.toString().trim()) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> submit(null) }
                .setOnCancelListener { submit(null) }
                .show()
        }
    }

    private fun sendText() {
        val text = findViewById<EditText>(R.id.textInput).text.toString()
        if (text.isEmpty()) {
            toast(getString(R.string.enter_text))
            return
        }
        val r = remote
        if (r == null) {
            toast(getString(R.string.need_tv_first))
            return
        }
        r.sendTextSmart(text)
    }

    private fun openApps() = sendOrToast { requestApps() }

    /**
     * Тачпад (v2.9). Режим зависит от протокола текущего сеанса:
     *  - Tizen (wss/ws) — мышь: свайп шлёт ДЕЛЬТЫ сдвига курсора (ProcessMouseDevice,
     *    семантика официальной либы Samsung — currentMoveDelta), тап — LeftClick;
     *  - старые ТВ (smart/legacy) — мыши в протоколе нет: свайп — стрелки,
     *    тап — OK (KEY_ENTER).
     *  Для Tizen — тумблер «мышь ⇄ стрелки»: если прошивка игнорирует мышь,
     *  стрелки в браузере ТВ тоже двигают указатель.
     */
    @SuppressLint("SetTextI18n")
    private fun showTouchpad() {
        val r = remote
        if (r == null || !r.isConnected()) {
            toast(getString(R.string.need_tv_first))
            return
        }
        val mouseSupported = r.supportsMouse()
        var padMouse = mouseSupported
        val d = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * d).toInt(), (10 * d).toInt(), (14 * d).toInt(), 0)
        }

        val pad = TouchpadView(
            this,
            mouseMode = padMouse,
            onMove = { dx, dy -> sendOrToast { sendMouseMove(dx, dy) } },
            onClick = {
                sendOrToast { if (padMouse) sendMouseClick() else sendKey("KEY_ENTER") }
            },
            onArrow = { key -> sendOrToast { sendKey(key) } },
            onLongClick = { sendOrToast { sendMouseRightClick() } }
        )
        box.addView(
            pad,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (270 * d).toInt())
        )

        val mouseHint = getString(R.string.mouse_hint)
        val arrowsHint = getString(R.string.arrows_hint)
        val hint = TextView(this).apply {
            textSize = 12f
            setPadding(0, (8 * d).toInt(), 0, 0)
            text = if (mouseSupported) mouseHint
            else getString(R.string.no_mouse_hint)
        }
        box.addView(hint)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun keyBtn(text: String, key: String): Button = Button(this).apply {
            this.text = text
            setOnClickListener { sendOrToast { sendKey(key) } }
        }
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins((2 * d).toInt(), (2 * d).toInt(), (2 * d).toInt(), (2 * d).toInt())
        }
        row.addView(keyBtn(getString(R.string.back_key), "KEY_RETURN"), lp)
        row.addView(keyBtn("HOME", "KEY_HOME"), lp)
        row.addView(keyBtn("OK", "KEY_ENTER"), lp)
        box.addView(row)

        // Тумблер режима для Tizen — страховка: если прошивка игнорирует ProcessMouseDevice
        if (mouseSupported) {
            box.addView(Button(this).apply {
                textSize = 12f
                text = getString(R.string.mode_mouse)
                setOnClickListener {
                    padMouse = !padMouse
                    pad.setMode(padMouse)
                    text = if (padMouse) getString(R.string.mode_mouse) else getString(R.string.mode_arrows)
                    hint.text = if (padMouse) mouseHint else arrowsHint
                    appendLog(getString(R.string.m_pad_mode_log, if (padMouse) getString(R.string.m_pad_mouse) else getString(R.string.m_pad_arrows)))
                }
            })
        }

        appendLog(getString(R.string.m_pad_mode_log, if (padMouse) getString(R.string.m_pad_mouse2) else getString(R.string.m_pad_arrows2)))
        AlertDialog.Builder(this)
            .setTitle(if (mouseSupported) getString(R.string.tp_mouse_title) else getString(R.string.tp_arrows_title))
            .setView(box)
            .setNegativeButton(getString(R.string.gen_close), null)
            .show()
    }

    // ---------- Версия приложения (обновления — в «Настройках») ----------

    private fun currentVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    private fun showApps(apps: List<SamsungRemote.AppInfo>, catalog: Boolean = false) {
        // Свои запомненные приложения всегда добавляем к результату ТВ (без дублей по имени):
        // пользователь может расширить список даже на прошивке без перечисления
        val manual = savedManualApps()
        val merged = mutableListOf<SamsungRemote.AppInfo>()
        val seen = mutableSetOf<String>()
        for (a in manual + apps) {
            val key = a.name.lowercase(Locale.ROOT).trim()
            if (key.isNotEmpty() && seen.add(key)) merged.add(a)
        }
        if (merged.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.app_list_title))
                .setMessage(getString(R.string.m_app_list_empty))
                .setPositiveButton(getString(R.string.log_btn)) { _, _ -> openLogDialog() }
                .setNegativeButton(getString(R.string.gen_close), null)
                .show()
            return
        }
        val names = merged.map { "${it.name}\n(${it.appId})" }.toTypedArray()
        val title = when {
            catalog && manual.isNotEmpty() -> getString(R.string.m_title_catalog_plus, merged.size)
            catalog -> getString(R.string.m_title_catalog, merged.size)
            manual.isNotEmpty() -> getString(R.string.m_title_apps_plus, merged.size)
            else -> getString(R.string.m_title_apps, merged.size)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(names) { _, i ->
                sendOrToast { launchApp(merged[i].appId, merged[i].name) }
            }
            .setNeutralButton(getString(R.string.own_app)) { _, _ -> showManualAppDialog() }
            .setNegativeButton(getString(R.string.gen_close), null)
            .show()
    }

    /** IP текущего ТВ (выбирается на экране «Настройки», prefs «host») */
    private fun currentTvHost(): String {
        return prefs.getString("host", null) ?: ""
    }

    /** Свои запомненные приложения текущего ТВ (prefs «myapps:<ip>», JSON-массив) */
    private fun savedManualApps(): List<SamsungRemote.AppInfo> {
        val host = currentTvHost()
        if (host.isEmpty()) return emptyList()
        val raw = prefs.getString("myapps:$host", null) ?: return emptyList()
        val out = mutableListOf<SamsungRemote.AppInfo>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                val nm = o.optString("name")
                if (id.isNotBlank()) out.add(SamsungRemote.AppInfo(id, nm.ifBlank { id }))
            }
        } catch (_: Exception) {
        }
        return out
    }

    /** Запомнить приложение, запущенное вручную (без дублей по ID и имени) */
    private fun rememberManualApp(id: String, name: String) {
        val host = currentTvHost()
        if (host.isEmpty()) return
        val list = savedManualApps().toMutableList()
        val key = name.lowercase(Locale.ROOT).trim()
        list.removeAll { it.appId == id || it.name.lowercase(Locale.ROOT).trim() == key }
        list.add(SamsungRemote.AppInfo(id, name))
        val arr = JSONArray()
        for (a in list) arr.put(JSONObject().put("id", a.appId).put("name", a.name))
        prefs.edit().putString("myapps:$host", arr.toString()).apply()
    }

    /** Ручной запуск приложения по ID или имени + запоминание в списке приложений */
    private fun showManualAppDialog() {
        val saved = savedManualApps()
        val input = EditText(this)
        input.hint = getString(R.string.id_dial_hint)
        input.inputType = InputType.TYPE_CLASS_TEXT
        val nameInput = EditText(this)
        nameInput.hint = getString(R.string.list_name_hint)
        nameInput.inputType = InputType.TYPE_CLASS_TEXT
        val remember = CheckBox(this)
        remember.text = getString(R.string.remember_app)
        remember.isChecked = true
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
            addView(nameInput)
            addView(remember)
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle(getString(R.string.manual_launch_title))
            .setView(box)
            .setPositiveButton(getString(R.string.gen_run)) { _, _ ->
                val v = input.text.toString().trim()
                if (v.isNotEmpty()) {
                    val nm = nameInput.text.toString().trim()
                    sendOrToast { launchApp(v, nm.ifEmpty { null }) }
                    if (remember.isChecked) {
                        rememberManualApp(v, nm.ifEmpty { v })
                        toast(getString(R.string.app_added))
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (saved.isNotEmpty()) {
            dlg.setNeutralButton(getString(R.string.m_forget_own, saved.size)) { _, _ ->
                prefs.edit().remove("myapps:${currentTvHost()}").apply()
                toast(getString(R.string.own_apps_forgot))
            }
        }
        dlg.show()
    }

    // ---------- Ссылка на экран ТВ (v3.0) ----------

    /**
     * Диалог «Открыть ссылку на ТВ»: любая ссылка — в браузер ТВ (Tizen),
     * YouTube-ссылка — экспериментально сразу в ТВ-приложение YouTube.
     * Если в буфере обмена лежит http-ссылка — подставляем её.
     */
    private fun showUrlDialog() {
        val urlInput = EditText(this).apply {
            hint = getString(R.string.url_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            maxLines = 1
        }
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
            if (!clip.isNullOrBlank() && clip.startsWith("http")) urlInput.setText(clip)
        } catch (_: Exception) {
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(urlInput)
            addView(TextView(this@MainActivity).apply {
                textSize = 12f
                setPadding(0, (8 * pad / 16), 0, 0)
                text = getString(R.string.m_url_help)
            })
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.url_title))
            .setView(box)
            .setPositiveButton(getString(R.string.url_browser_btn)) { _, _ ->
                val u = urlInput.text.toString().trim()
                if (u.isEmpty()) toast(getString(R.string.enter_url)) else sendOrToast { launchBrowserUrl(u) }
            }
            .setNeutralButton(getString(R.string.url_youtube_btn)) { _, _ ->
                val u = urlInput.text.toString().trim()
                if (u.isEmpty()) toast(getString(R.string.enter_url)) else sendOrToast { launchYouTubeVideo(u) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------- Мои кнопки — макросы (v3.0) ----------

    private data class MacroStep(
        val type: String,   // "key" | "app"
        val id: String,     // appId для "app"
        val name: String,   // название приложения для "app"
        val key: String     // KEY_* для "key"
    )

    private data class Macro(val name: String, val steps: List<MacroStep>)

    /** Клавиши, доступные в редакторе макросов */
    private val MACRO_KEYS = arrayOf(
        "KEY_VOLUP", "KEY_VOLDOWN", "KEY_MUTE",
        "KEY_PLAY", "KEY_PAUSE", "KEY_STOP", "KEY_FF", "KEY_REW",
        "KEY_CHUP", "KEY_CHDOWN", "KEY_INFO", "KEY_GUIDE", "KEY_CHLIST", "KEY_PRECH",
        "KEY_UP", "KEY_DOWN", "KEY_LEFT", "KEY_RIGHT", "KEY_ENTER",
        "KEY_HOME", "KEY_RETURN", "KEY_EXIT", "KEY_MENU", "KEY_TOOLS",
        "KEY_HDMI_1", "KEY_HDMI_2", "KEY_HDMI_3"
    )

    private fun loadMacros(): List<Macro> {
        val host = currentTvHost()
        if (host.isEmpty()) return emptyList()
        val raw = prefs.getString("macros:$host", null) ?: return emptyList()
        val out = mutableListOf<Macro>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name")
                val stepsArr = o.optJSONArray("steps") ?: continue
                val steps = mutableListOf<MacroStep>()
                for (j in 0 until stepsArr.length()) {
                    val so = stepsArr.optJSONObject(j) ?: continue
                    steps.add(
                        MacroStep(
                            so.optString("type"),
                            so.optString("id"),
                            so.optString("name"),
                            so.optString("key")
                        )
                    )
                }
                if (name.isNotBlank() && steps.isNotEmpty()) out.add(Macro(name, steps))
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun saveMacros(list: List<Macro>) {
        val host = currentTvHost()
        if (host.isEmpty()) return
        val arr = JSONArray()
        for (m in list) {
            val steps = JSONArray()
            for (s in m.steps) {
                steps.put(
                    JSONObject().put("type", s.type).put("id", s.id)
                        .put("name", s.name).put("key", s.key)
                )
            }
            arr.put(JSONObject().put("name", m.name).put("steps", steps))
        }
        prefs.edit().putString("macros:$host", arr.toString()).apply()
    }

    private fun saveMacro(existing: Macro?, updated: Macro) {
        val list = loadMacros().toMutableList()
        if (existing != null) list.removeAll { it.name == existing.name }
        list.removeAll { it.name == updated.name }
        list.add(updated)
        saveMacros(list)
        renderMacrosRow()
        toast(getString(R.string.m_macro_saved, updated.name))
    }

    /** Ряд кнопок макросов на главном экране: тап — выполнить, удержание — редактор */
    private fun renderMacrosRow() {
        val row = findViewById<LinearLayout>(R.id.macrosRow)
        row.removeAllViews()
        val ms = loadMacros()
        row.visibility = if (ms.isEmpty()) View.GONE else View.VISIBLE
        val d = resources.displayMetrics.density
        for (m in ms) {
            val b = Button(this).apply {
                text = m.name
                isAllCaps = false
                setOnClickListener { runMacro(m) }
                setOnLongClickListener { showMacroEditor(m); true }
            }
            ThemeEngine.styleDynamicButton(b, prefs) // v3.1: кнопки создаются кодом — стилизуем под тему
            row.addView(
                b,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins((3 * d).toInt(), 0, (3 * d).toInt(), 0) }
            )
        }
    }

    /** Выполнение макроса: шаги по очереди с паузой (запуск приложения / клавиша) */
    private fun runMacro(m: Macro) {
        val r = remote
        if (r == null || !r.isConnected()) {
            toast(getString(R.string.need_tv_first))
            return
        }
        toast(getString(R.string.m_macro_named, m.name))
        Thread {
            appendLogUi(getString(R.string.m_macro_run_log, m.name))
            m.steps.forEachIndexed { i, s ->
                appendLogUi(
                    getString(R.string.m_macro_step_fmt, m.name, i + 1, m.steps.size,
                        if (s.type == "app") getString(R.string.m_step_run_fmt, s.name, s.id) else s.key)
                )
                try {
                    if (s.type == "app") r.launchApp(s.id, s.name) else r.sendKey(s.key)
                } catch (e: Exception) {
                    appendLogUi(getString(R.string.m_macro_err, e.message))
                }
                try {
                    Thread.sleep(900)
                } catch (_: InterruptedException) {
                }
            }
            appendLogUi(getString(R.string.m_macro_done, m.name))
        }.start()
    }

    /** appendLog из фонового потока — всегда через UI-поток */
    private fun appendLogUi(line: String) {
        runOnUiThread { appendLog(line) }
    }

    /** Список макросов: тап по пункту — выполнение */
    private fun showMacrosDialog() {
        val ms = loadMacros()
        val items = if (ms.isEmpty())
            arrayOf(getString(R.string.no_macros))
        else
            ms.map { getString(R.string.m_macro_list_item, it.name, it.steps.size) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.macros_title))
            .setMessage(getString(R.string.macros_hint))
            .setItems(items) { _, i -> if (ms.isNotEmpty()) runMacro(ms[i]) }
            .setPositiveButton(getString(R.string.create_macro)) { _, _ -> showMacroEditor(null) }
            .setNegativeButton(getString(R.string.gen_close), null)
            .show()
    }

    /** Редактор макроса: название + шаги («+ клавиша», «+ приложение»), удаление шага — удержание */
    private fun showMacroEditor(existing: Macro?) {
        val d = resources.displayMetrics.density
        val steps = existing?.steps?.toMutableList() ?: mutableListOf()
        val nameInput = EditText(this).apply {
            hint = getString(R.string.macro_name_hint)
            setText(existing?.name ?: "")
        }
        val stepsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        fun redraw() {
            stepsBox.removeAllViews()
            if (steps.isEmpty()) {
                stepsBox.addView(TextView(this@MainActivity).apply {
                    textSize = 12f
                    text = getString(R.string.no_steps)
                })
            }
            steps.forEachIndexed { i, s ->
                stepsBox.addView(TextView(this@MainActivity).apply {
                    textSize = 13f
                    setPadding(0, (2 * d).toInt(), 0, (2 * d).toInt())
                    text = "${i + 1}. " + if (s.type == "app")
                        getString(R.string.m_step_run_fmt2, s.name, s.id) else getString(R.string.m_step_key_fmt, s.key)
                    setOnLongClickListener {
                        steps.removeAt(i)
                        redraw()
                        true
                    }
                })
            }
        }
        redraw()

        val btnKey = Button(this).apply { text = getString(R.string.add_key_btn) }
        val btnApp = Button(this).apply { text = getString(R.string.add_app_btn) }
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btnKey, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(btnApp, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * d).toInt(), (8 * d).toInt(), (14 * d).toInt(), 0)
            addView(nameInput)
            addView(stepsBox)
            addView(btnRow)
            addView(TextView(this@MainActivity).apply {
                textSize = 11f
                text = getString(R.string.del_step_hint)
            })
        }

        btnKey.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.key_for_macro))
                .setItems(MACRO_KEYS) { _, k ->
                    steps.add(MacroStep("key", "", "", MACRO_KEYS[k]))
                    redraw()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        btnApp.setOnClickListener {
            val saved = savedManualApps()
            val names = saved.map { "${it.name} (${it.appId})" }.toMutableList()
            names.add(getString(R.string.manual_id))
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.app_for_macro))
                .setItems(names.toTypedArray()) { _, i ->
                    if (i < saved.size) {
                        steps.add(MacroStep("app", saved[i].appId, saved[i].name, ""))
                        redraw()
                    } else {
                        val inp = EditText(this).apply {
                            hint = getString(R.string.id_dial_hint_short)
                            inputType = InputType.TYPE_CLASS_TEXT
                        }
                        AlertDialog.Builder(this)
                            .setTitle(getString(R.string.id_or_name_title))
                            .setView(inp)
                            .setPositiveButton(getString(R.string.gen_add)) { _, _ ->
                                val v = inp.text.toString().trim()
                                if (v.isNotEmpty()) {
                                    steps.add(MacroStep("app", v, v, ""))
                                    redraw()
                                }
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(if (existing == null) getString(R.string.new_macro) else getString(R.string.m_macro_named, existing.name))
            .setView(box)
            .setPositiveButton(getString(R.string.gen_save)) { _, _ ->
                val nm = nameInput.text.toString().trim().ifEmpty { getString(R.string.macro_default_name) }
                if (steps.isEmpty()) {
                    toast(getString(R.string.macro_no_steps))
                    return@setPositiveButton
                }
                saveMacro(existing, Macro(nm, steps.toList()))
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (existing != null) {
            builder.setNeutralButton(getString(R.string.gen_delete)) { _, _ ->
                saveMacros(loadMacros().filter { it.name != existing.name })
                renderMacrosRow()
                toast(getString(R.string.macro_deleted))
            }
        }
        builder.show()
    }

    /**
     * Кнопка POWER — умный вкл/выкл:
     *  - сеанс с ТВ активен → выключаем (каскад KEY_POWEROFF → KEY_POWER → KEY_POWERTOGGLE,
     *    т.к. разные прошивки принимают разные ключи; для старых ТВ — KEY_POWER);
     *  - ТВ недоступен → Wake-on-LAN и автоматическое переподключение.
     */
    private fun pressPower() {
        val host = prefs.getString("host", null)
        if (host.isNullOrBlank()) {
            toast(getString(R.string.need_tv_settings))
            return
        }
        val r = remote
        if (r != null && r.isConnected()) {
            r.powerOffSmart()
            return
        }
        wakeTv(host)
    }

    /** Включение ТВ: Wake-on-LAN (MAC — из API/ARP-кэша/вручную) + автоподключение */
    private fun wakeTv(host: String) {
        statusView.text = getString(R.string.wake_start)
        Thread {
            var mac = prefs.getString("mac", null)
            if (mac.isNullOrBlank()) mac = learnMac(host)
            if (mac.isNullOrBlank()) {
                runOnUiThread {
                    statusView.text = getString(R.string.mac_unknown)
                    toast(getString(R.string.mac_unknown_hint))
                }
                return@Thread
            }
            runOnUiThread { statusView.text = getString(R.string.m_wol_enabling, mac) }
            SamsungRemote.wakeOnLan(mac, host.substringBeforeLast('.') + ".255")
            var awake = false
            for (i in 1..10) {
                Thread.sleep(1500)
                if (TvDiscovery.verify(host, 1200) != null) {
                    awake = true
                    break
                }
            }
            runOnUiThread {
                if (awake) {
                    statusView.text = getString(R.string.m_waking)
                    appendLog(getString(R.string.wol_answered))
                    connectToHost(host)
                } else {
                    statusView.text = getString(R.string.wake_fail)
                    appendLog(getString(R.string.m_wol_no_reply, mac))
                    toast(getString(R.string.wake_fail_hint))
                }
            }
        }.start()
    }

    /**
     * MAC ТВ для Wake-on-LAN: сначала REST /api/v2/ (device.wifiMac),
     * если прошивка не отдала — ARP-кэш телефона (запись появляется после
     * любого TCP-контакта с ТВ). Найденный MAC сохраняется.
     */
    private fun learnMac(host: String): String? {
        try {
            val conn = URL("http://$host:8001/api/v2/").openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val mac = JSONObject(body).getJSONObject("device").optString("wifiMac")
            if (mac.isNotBlank() && mac != "Unknown" && mac.contains(":")) {
                prefs.edit().putString("mac", mac).apply()
                return mac
            }
        } catch (_: Exception) {
        }
        try {
            val arp = File("/proc/net/arp").readText()
            for (line in arp.split('\n')) {
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 4 && parts[0] == host &&
                    parts[3].contains(":") && parts[3] != "00:00:00:00:00:00"
                ) {
                    prefs.edit().putString("mac", parts[3]).apply()
                    runOnUiThread { appendLog(getString(R.string.m_mac_arp, parts[3])) }
                    return parts[3]
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    private fun askMac() {
        val input = EditText(this)
        input.hint = "C0:23:8D:49:B3:BC"
        input.setText(prefs.getString("mac", ""))
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.mac_title))
            .setMessage(getString(R.string.mac_message))
            .setView(input)
            .setPositiveButton(getString(R.string.gen_save)) { _, _ ->
                prefs.edit().putString("mac", input.text.toString().trim()).apply()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Копирует журнал в буфер обмена — чтобы вставить его в чат для диагностики */
    private fun copyLog() {
        val text = logLines.joinToString("\n").ifBlank { getString(R.string.log_empty) }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("tizenpult-log", text))
        toast(getString(R.string.log_copied))
    }

    /** Диалог журнала: читаемый лог без прыжков + отправка произвольной KEY_* клавиши для тестов */
    private fun openLogDialog() {
        val d = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((10 * d).toInt(), (6 * d).toInt(), (10 * d).toInt(), 0)
        }

        // Тестовая отправка любой клавиши: вводишь VOLDOWN — уходит KEY_VOLDOWN
        val keyInput = EditText(this).apply {
            hint = getString(R.string.key_test_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
        }
        val sendBtn = Button(this).apply { text = getString(R.string.gen_send) }
        val keyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(keyInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(sendBtn)
        }
        container.addView(keyRow)

        val scroll = ScrollView(this)
        val tv = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setTextColor(0xFF9FE89F.toInt())
            setBackgroundColor(0xFF111111.toInt())
            setPadding((8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
            text = logLines.joinToString("\n").ifBlank { getString(R.string.log_blank) }
        }
        scroll.addView(tv)
        container.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (340 * d).toInt()
            )
        )
        logDialogText = tv
        logDialogScroll = scroll

        sendBtn.setOnClickListener {
            val raw = keyInput.text.toString().trim()
            if (raw.isNotEmpty()) {
                sendCustomKey(raw)
                keyInput.setText("")
            }
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.log_dialog_title))
            .setView(container)
            .setPositiveButton(getString(R.string.gen_copy)) { _, _ -> copyLog() }
            .setNeutralButton(getString(R.string.gen_clear)) { _, _ -> logLines.clear(); refreshLogText() }
            .setNegativeButton(getString(R.string.gen_close), null)
            .setOnDismissListener {
                logDialogText = null
                logDialogScroll = null
            }
            .show()
    }

    /** Тест имени клавиши: «VOLDOWN» превратится в KEY_VOLDOWN */
    private fun sendCustomKey(raw: String) {
        val norm = raw.uppercase(Locale.ROOT).replace(' ', '_')
        val key = if (norm.startsWith("KEY_")) norm else "KEY_$norm"
        appendLog(getString(R.string.m_key_test_log, key))
        sendOrToast { sendKey(key) }
    }

    private fun refreshLogText() {
        val tv = logDialogText ?: return
        tv.text = logLines.joinToString("\n").ifBlank { getString(R.string.log_blank2) }
        val sc = logDialogScroll ?: return
        sc.post { sc.fullScroll(View.FOCUS_DOWN) }
    }

    private fun appendLog(line: String) {
        logLines.addLast(line)
        while (logLines.size > 200) logLines.removeFirst()
        refreshLogText()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
