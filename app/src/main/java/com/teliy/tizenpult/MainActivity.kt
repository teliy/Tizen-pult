package com.teliy.tizenpult

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private var remote: SamsungRemote? = null

    private lateinit var ipInput: EditText
    private lateinit var statusView: TextView

    private val logLines = ArrayDeque<String>()
    private var logDialogText: TextView? = null
    private var logDialogScroll: ScrollView? = null

    /** APK, ожидающий установки после выдачи разрешения «неизвестные приложения» */
    private var pendingInstall: File? = null

    /** ТВ, найденные во время прошлых поисков (хранится в настройках) */
    private data class KnownTv(
        val ip: String,
        val name: String,
        val model: String,
        val lastSeen: Long
    )

    // ---------- Выбор ТВ сверху ----------

    /** Строка выпадающего списка телевизоров */
    private data class TvOption(val label: String, val ip: String, val supported: Boolean)

    private lateinit var tvSpinner: Spinner
    private var tvOptions: List<TvOption> = emptyList()
    private var suppressSpin = false

    /** Старые Samsung (2014 и т.п.), найденные последним поиском — не подключаются */
    private var lastUnsupported: List<TvDiscovery.Result> = emptyList()

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("tizenpult", Context.MODE_PRIVATE)
        ipInput = findViewById(R.id.ipInput)
        statusView = findViewById(R.id.statusView)

        ipInput.setText(prefs.getString("host", ""))

        // Выпадающий список телевизоров в верхней строке
        tvSpinner = findViewById(R.id.tvSpinner)
        tvSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (suppressSpin) return
                val opt = tvOptions.getOrNull(pos) ?: return
                if (opt.ip.isEmpty()) {
                    // Пункт-подсказка «ТВ не найден — нажмите Найти» — запускаем поиск
                    findTv(manual = true)
                    return
                }
                if (!opt.supported) {
                    showUnsupportedDialog(opt)
                    return
                }
                prefs.edit().putString("host", opt.ip).apply()
                ipInput.setText(opt.ip)
                connectToHost(opt.ip)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        findViewById<Button>(R.id.btnConnect).setOnClickListener { connectManual() }
        findViewById<Button>(R.id.btnFind).setOnClickListener { findTv(manual = true) }
        findViewById<Button>(R.id.btnSendText).setOnClickListener { sendText() }
        findViewById<Button>(R.id.btnApps).setOnClickListener { openApps() }
        findViewById<Button>(R.id.btnLog).setOnClickListener { openLogDialog() }
        findViewById<Button>(R.id.btnUpdate).setOnClickListener { checkUpdate() }
        findViewById<TextView>(R.id.versionView).text = "Версия ${currentVersionName()}"

        val btnPowerOn = findViewById<Button>(R.id.btnPowerOn)
        btnPowerOn.setOnClickListener { powerOn() }
        // Долгое нажатие — ручной ввод MAC (если REST выдал «Unknown» или закрыт)
        btnPowerOn.setOnLongClickListener {
            askMac()
            true
        }

        // Автопоиск ТВ и подключение при запуске приложения
        refreshTvSpinner(prefs.getString("host", null))
        findTv(manual = false)
    }

    override fun onResume() {
        super.onResume()
        // Возврат из настроек «установка неизвестных приложений» — продолжаем установку
        val f = pendingInstall ?: return
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) return
        pendingInstall = null
        if (f.exists()) startInstall(f)
    }

    /** Кнопки с андроид:tag="KEY_..." в разметке обрабатываются здесь */
    fun onKeyClick(v: View) {
        val key = v.tag as? String ?: return
        sendOrToast { sendKey(key) }
    }

    private fun sendOrToast(action: SamsungRemote.() -> Unit) {
        val r = remote
        if (r == null) {
            toast("Сначала подключитесь к ТВ")
            return
        }
        try {
            r.action()
        } catch (e: Exception) {
            toast("Ошибка: ${e.message}")
            appendLog("!! исключение при отправке: ${e.message}")
        }
    }

    private fun connectManual() {
        val host = ipInput.text.toString().trim()
        if (host.isEmpty()) {
            toast("Введите IP или нажмите «Найти ТВ»")
            return
        }
        prefs.edit().putString("host", host).apply()
        connectToHost(host)
    }

    /**
     * Автопоиск ТВ: сохранённый IP + прошлые находки + SSDP + скан всех подсетей.
     * Если найдено несколько ТВ — показываем список; если ни одного и известен MAC —
     * пробуждение по сети и повторная проверка.
     * Каждый этап пишется в журнал — по нему можно понять, почему ТВ не нашёлся.
     * Вызывается при старте приложения и по кнопке «Найти ТВ».
     */
    private fun findTv(manual: Boolean) {
        statusView.text = if (manual) "Поиск ТВ в сети…" else "Автопоиск ТВ при запуске…"
        Thread {
            var report = TvDiscovery.discoverAll(
                applicationContext,
                prefs.getString("host", null),
                loadKnown().map { it.ip }
            ) { p ->
                runOnUiThread { statusView.text = "🔎 $p" }
            }

            // Подробности всех этапов — в журнал (для диагностики в чат)
            val reportToLog = report
            runOnUiThread {
                appendLog("— поиск ТВ ${if (manual) "(вручную)" else "(автозапуск)"} —")
                for (line in reportToLog.log) appendLog(line)
            }

            // Ничего не нашли — возможно, ТВ спит: пробуждаем и проверяем ещё раз
            if (report.found.isEmpty()) {
                val mac = prefs.getString("mac", null)
                val saved = prefs.getString("host", null)
                if (!mac.isNullOrBlank() && !saved.isNullOrBlank()) {
                    runOnUiThread { statusView.text = "ТВ не отвечает — отправляю Wake-on-LAN…" }
                    SamsungRemote.wakeOnLan(mac)
                    Thread.sleep(5000)
                    val r = TvDiscovery.verify(saved, 2000)
                    if (r != null) {
                        report = TvDiscovery.Report(
                            listOf(r.copy(source = "после Wake-on-LAN")),
                            emptyList(),
                            listOf("WoL: ТВ проснулся и ответил ($saved)")
                        )
                        runOnUiThread { appendLog(report.log.first()) }
                    }
                }
            }

            rememberFound(report.found)
            lastUnsupported = report.unsupported

            runOnUiThread {
                when {
                    report.found.size == 1 -> {
                        val tv = report.found[0]
                        prefs.edit().putString("host", tv.ip).apply()
                        ipInput.setText(tv.ip)
                        refreshTvSpinner(tv.ip)
                        connectToHost(tv.ip)
                    }
                    report.found.size > 1 -> {
                        statusView.text = "Найдено ТВ: ${report.found.size} — выберите в списке сверху"
                        refreshTvSpinner(prefs.getString("host", null))
                    }
                    else -> {
                        statusView.text = "ТВ не найден. Включите ТВ, проверьте Wi-Fi или введите IP вручную"
                        refreshTvSpinner(prefs.getString("host", null))
                    }
                }
            }
        }.start()
    }

    /** Сохраняем найденные сейчас ТВ в историю (показываются, даже когда выключены) */
    private fun rememberFound(found: List<TvDiscovery.Result>) {
        if (found.isEmpty()) return
        val now = System.currentTimeMillis()
        val hist = loadKnown()
        for (r in found) {
            hist.removeAll { it.ip == r.ip }
            hist.add(0, KnownTv(r.ip, r.name, r.model, now))
        }
        saveKnown(hist.distinctBy { it.ip }.take(20))
    }

    private fun loadKnown(): MutableList<KnownTv> = try {
        val arr = JSONArray(prefs.getString("knownTvs", "[]") ?: "[]")
        (0 until arr.length()).map {
            val x = arr.getJSONObject(it)
            KnownTv(x.getString("ip"), x.optString("name"), x.optString("model"), x.optLong("lastSeen"))
        }.toMutableList()
    } catch (_: Exception) {
        mutableListOf()
    }

    private fun saveKnown(list: List<KnownTv>) {
        val arr = JSONArray()
        for (k in list) {
            arr.put(JSONObject().apply {
                put("ip", k.ip)
                put("name", k.name)
                put("model", k.model)
                put("lastSeen", k.lastSeen)
            })
        }
        prefs.edit().putString("knownTvs", arr.toString()).apply()
    }

    /**
     * Пересобирает выпадающий список ТВ сверху:
     * рабочие ТВ (найденные сейчас + история), затем старые (не поддерживаются).
     */
    private fun refreshTvSpinner(selectIp: String? = null) {
        val opts = mutableListOf<TvOption>()
        val seen = mutableSetOf<String>()
        for (k in loadKnown()) {
            if (seen.add(k.ip)) {
                val mark = if (selectIp == k.ip) "▸ " else ""
                opts.add(TvOption("$mark${k.name} — ${k.model.ifBlank { "Samsung" }} — ${k.ip}", k.ip, true))
            }
        }
        for (r in lastUnsupported) {
            if (seen.add(r.ip)) {
                opts.add(
                    TvOption(
                        "${r.name} — ${r.model.ifBlank { "Samsung" }} — ${r.ip} — старая модель, не поддерживается",
                        r.ip,
                        false
                    )
                )
            }
        }
        if (opts.isEmpty()) {
            opts.add(TvOption("ТВ не найден — нажмите «Найти»", "", false))
        }
        tvOptions = opts

        suppressSpin = true
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, opts.map { it.label })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        tvSpinner.adapter = adapter
        val sel = opts.indexOfFirst { it.ip == selectIp }
        if (sel >= 0) tvSpinner.setSelection(sel)
        // onItemSelected приходит после отрисовки — снимаем блокировку позже
        tvSpinner.post { suppressSpin = false }
    }

    /** Старые ТВ (2014 и раньше): у них другой протокол — объясняем, но даём попробовать */
    private fun showUnsupportedDialog(opt: TvOption) {
        AlertDialog.Builder(this)
            .setTitle("ТВ со старым протоколом")
            .setMessage(
                "${opt.label}\n\nЭта модель отвечает только по старому протоколу Samsung " +
                    "(до 2015 года). Этот пульт управляет ТВ с Tizen (2016 и новее)."
            )
            .setPositiveButton("Понятно", null)
            .setNeutralButton("Всё равно попробовать") { _, _ ->
                ipInput.setText(opt.ip)
                prefs.edit().putString("host", opt.ip).apply()
                connectToHost(opt.ip)
            }
            .show()
    }

    private fun connectToHost(host: String) {
        // Может вызываться из фонового потока (автопоиск) — UI трогаем только через runOnUiThread
        runOnUiThread { statusView.text = "Подключение к $host..." }
        remote?.close()
        remote = SamsungRemote(
            host = host,
            token = prefs.getString("token", null),
            onStatus = { s -> runOnUiThread { statusView.text = s } },
            onToken = { t ->
                prefs.edit().putString("token", t).apply()
            },
            onApps = { apps -> runOnUiThread { showApps(apps) } },
            onLog = { s -> runOnUiThread { appendLog(s) } }
        )
        remote?.connect()
        fetchDeviceInfo(host)
        runOnUiThread {
            statusView.text = "Подключение к $host..."
            if (logLines.isNotEmpty()) appendLog("— новый сеанс —")
        }
    }

    private fun sendText() {
        val text = findViewById<EditText>(R.id.textInput).text.toString()
        if (text.isEmpty()) {
            toast("Введите текст")
            return
        }
        val r = remote
        if (r == null) {
            toast("Сначала подключитесь к ТВ")
            return
        }
        r.sendTextSmart(text)
    }

    private fun openApps() = sendOrToast { requestApps() }

    // ---------- Обновление приложения ----------

    private fun currentVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    /** Кнопка «Проверить обновления»: GitHub API → сравнение версий → диалог */
    private fun checkUpdate() {
        statusView.text = "Проверка обновлений…"
        Thread {
            try {
                val rel = UpdateChecker.fetchLatest()
                val cur = currentVersionName()
                if (!UpdateChecker.isNewer(rel.version, cur)) {
                    runOnUiThread {
                        statusView.text = "Обновлений нет"
                        AlertDialog.Builder(this)
                            .setTitle("У вас актуальная версия")
                            .setMessage("Установлена версия $cur.\nПоследний релиз на GitHub: ${rel.tag}.")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                    return@Thread
                }
                if (rel.apkUrl.isNullOrBlank()) {
                    runOnUiThread {
                        statusView.text = "В релизе ${rel.tag} нет APK-файла"
                        toast("В релизе ${rel.tag} нет APK-файла")
                    }
                    return@Thread
                }
                val url = rel.apkUrl
                val mb = rel.apkSize / 1048576f
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle("Доступно обновление ${rel.version}")
                        .setMessage(
                            "Установлена версия $cur.\n" +
                            "Новая версия: ${rel.version} (${String.format(Locale.getDefault(), "%.1f", mb)} МБ).\n\n" +
                            "Скачать с GitHub и установить?"
                        )
                        .setPositiveButton("Скачать и установить") { _, _ -> downloadAndInstall(url, rel.version) }
                        .setNegativeButton("Позже", null)
                        .show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusView.text = "Ошибка проверки обновлений"
                    AlertDialog.Builder(this)
                        .setTitle("Не удалось проверить обновления")
                        .setMessage("${e.message}\n\nПроверьте интернет и попробуйте ещё раз.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }.start()
    }

    /** Скачивание APK в папку приложения с прогрессом в строке статуса */
    private fun downloadAndInstall(url: String, version: String) {
        statusView.text = "Скачивание v$version…"
        Thread {
            try {
                val dest = File(getExternalFilesDir(null), "update-$version.apk")
                UpdateChecker.download(url, dest) { done, total ->
                    val mb = 1048576f
                    val txt = if (total > 0)
                        String.format(Locale.getDefault(), "Скачивание v%s: %.1f / %.1f МБ", version, done / mb, total / mb)
                    else
                        String.format(Locale.getDefault(), "Скачивание v%s: %.1f МБ", version, done / mb)
                    runOnUiThread { statusView.text = txt }
                }
                pendingInstall = dest
                runOnUiThread {
                    statusView.text = "Скачано ${dest.name} — запускаю установку"
                    startInstall(dest)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusView.text = "Ошибка скачивания: ${e.message}"
                    toast("Ошибка скачивания: ${e.message}")
                }
            }
        }.start()
    }

    /** Запуск системного установщика APK через FileProvider */
    private fun startInstall(apk: File) {
        // Android 8+: требуется разрешение «устанавливать неизвестные приложения».
        // После возврата из настроек установка продолжится в onResume().
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            pendingInstall = apk
            toast("Разрешите установку для Tizen-Пульт в открывшихся настройках")
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                )
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
                } catch (e: Exception) {
                    toast("Откройте: Настройки → Приложения → Tizen-Пульт → Устанавливать неизвестные приложения")
                }
            }
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            toast("Не удалось запустить установщик: ${e.message}")
        }
    }

    private fun showApps(apps: List<SamsungRemote.AppInfo>) {
        if (apps.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Список приложений")
                .setMessage("ТВ не выдал список приложений.\nПодробности по каждой стратегии — в «Журнале связи с ТВ».")
                .setPositiveButton("Журнал") { _, _ -> openLogDialog() }
                .setNegativeButton("Закрыть", null)
                .show()
            return
        }
        val names = apps.map { "${it.name}\n(${it.appId})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Приложения на ТВ (${apps.size})")
            .setItems(names) { _, i ->
                sendOrToast { launchApp(apps[i].appId) }
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    /** Включение ТВ по Wake-on-LAN (нужен MAC: автоматом из API или вручную) */
    private fun powerOn() {
        val mac = prefs.getString("mac", null)
        if (mac.isNullOrBlank()) {
            toast("MAC неизвестен: подключитесь к ТВ или удерживайте кнопку для ввода MAC")
            return
        }
        statusView.text = "Отправка Wake-on-LAN на $mac..."
        Thread {
            val ok = SamsungRemote.wakeOnLan(mac)
            runOnUiThread {
                statusView.text = if (ok) "WoL отправлен — подождите 2–5 секунд" else "Ошибка отправки WoL"
            }
        }.start()
    }

    private fun askMac() {
        val input = EditText(this)
        input.hint = "C0:23:8D:49:B3:BC"
        input.setText(prefs.getString("mac", ""))
        AlertDialog.Builder(this)
            .setTitle("MAC-адрес телевизора")
            .setMessage("Смотрите на ТВ: Настройки → Общие → Сеть → Состояние сети")
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ ->
                prefs.edit().putString("mac", input.text.toString().trim()).apply()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /** REST /api/v2/ — определяем MAC ТВ автоматически для Wake-on-LAN */
    private fun fetchDeviceInfo(host: String) {
        Thread {
            try {
                val conn = URL("http://$host:8001/api/v2/").openConnection() as HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val mac = JSONObject(body).getJSONObject("device").optString("wifiMac")
                if (mac.isNotBlank()) prefs.edit().putString("mac", mac).apply()
            } catch (_: Exception) {
                // REST закрыт или ТВ выключен — MAC можно ввести вручную
            }
        }.start()
    }

    /** Копирует журнал в буфер обмена — чтобы вставить его в чат для диагностики */
    private fun copyLog() {
        val text = logLines.joinToString("\n").ifBlank { "Лог пуст" }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("tizenpult-log", text))
        toast("Лог скопирован — вставьте его в чат")
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
            hint = "Тест клавиши: VOLDOWN, HOME, HDMI..."
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
        }
        val sendBtn = Button(this).apply { text = "Отправить" }
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
            text = logLines.joinToString("\n").ifBlank { "(пусто — нажмите «Подключ.»)" }
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
            .setTitle("Журнал связи с ТВ")
            .setView(container)
            .setPositiveButton("Копировать") { _, _ -> copyLog() }
            .setNeutralButton("Очистить") { _, _ -> logLines.clear(); refreshLogText() }
            .setNegativeButton("Закрыть", null)
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
        appendLog("тест клавиши: $key")
        sendOrToast { sendKey(key) }
    }

    private fun refreshLogText() {
        val tv = logDialogText ?: return
        tv.text = logLines.joinToString("\n").ifBlank { "(пусто)" }
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
