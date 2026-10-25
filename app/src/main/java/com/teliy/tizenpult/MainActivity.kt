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
import android.widget.CheckBox
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
        val lastSeen: Long,
        val legacy: Boolean = false
    )

    // ---------- Выбор ТВ сверху ----------

    /** Строка выпадающего списка телевизоров */
    private data class TvOption(val label: String, val ip: String)

    private lateinit var tvSpinner: Spinner
    private var tvOptions: List<TvOption> = emptyList()
    private var suppressSpin = false

    /** Идет ли сейчас подключение (защита от двойных тапов) */
    @Volatile private var connecting = false

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
        btnPowerOn.setOnClickListener { pressPower() }
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
                    SamsungRemote.wakeOnLan(mac, saved.substringBeforeLast('.') + ".255")
                    Thread.sleep(5000)
                    val r = TvDiscovery.verify(saved, 2000)
                    if (r != null) {
                        report = TvDiscovery.Report(
                            listOf(r.copy(source = "после Wake-on-LAN")),
                            listOf("WoL: ТВ проснулся и ответил ($saved)")
                        )
                        runOnUiThread { appendLog(report.log.first()) }
                    }
                }
            }

            rememberFound(report.found)

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
            hist.add(0, KnownTv(r.ip, r.name, r.model, now, r.legacy))
        }
        saveKnown(hist.distinctBy { it.ip }.take(20))
    }

    private fun loadKnown(): MutableList<KnownTv> = try {
        val arr = JSONArray(prefs.getString("knownTvs", "[]") ?: "[]")
        (0 until arr.length()).map {
            val x = arr.getJSONObject(it)
            KnownTv(x.getString("ip"), x.optString("name"), x.optString("model"), x.optLong("lastSeen"), x.optBoolean("legacy"))
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
                put("legacy", k.legacy)
            })
        }
        prefs.edit().putString("knownTvs", arr.toString()).apply()
    }

    /**
     * Пересобирает выпадающий список ТВ сверху: все найденные/сохранённые ТВ —
     * и Tizen, и старые (2011–2015) — со всеми подключаемы по своему протоколу.
     */
    private fun refreshTvSpinner(selectIp: String? = null) {
        val opts = mutableListOf<TvOption>()
        val seen = mutableSetOf<String>()
        for (k in loadKnown()) {
            if (seen.add(k.ip)) {
                val mark = if (selectIp == k.ip) "▸ " else ""
                val old = if (k.legacy) " — старый протокол" else ""
                opts.add(TvOption("$mark${k.name} — ${k.model.ifBlank { "Samsung" }} — ${k.ip}$old", k.ip))
            }
        }
        if (opts.isEmpty()) {
            opts.add(TvOption("ТВ не найден — нажмите «Найти»", ""))
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
            runOnUiThread { statusView.text = "Подключение уже идет…" }
            return
        }
        connecting = true
        Thread {
            try {
                runOnUiThread {
                    statusView.text = "Подключение к $host…"
                    if (logLines.isNotEmpty()) appendLog("— новый сеанс —")
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
                    appendLog("порты ТВ: 8002=$p8002 (Tizen TLS), 8001=$p8001 (Tizen), 55000=$p55000 (старый), 8080=$p8080, 8000=$p8000 (Smart View 2.0)")
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
                            appendLog("✔ рабочий протокол: $proto — запомнен для $host")
                        }
                        learnMac(host) // MAC для Wake-on-LAN — на будущее
                        return@Thread
                    }
                }
                runOnUiThread {
                    statusView.text = "Не удалось подключиться — см. журнал"
                    appendLog("✖ ни один протокол не подключился (8002/8001/55000/8080)")
                }
            } finally {
                connecting = false
            }
        }.start()
    }

    /** Одна попытка: создаем клиент и ждём подтверждения сеанса (до 8/30/150 с) */
    private fun tryProto(host: String, proto: String): Boolean {
        runOnUiThread {
            appendLog("— пробую протокол $proto —")
            statusView.text = "Подключение к $host ($proto)…"
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
            onPin = { retry, submit -> showPinDialog(retry, submit) }
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
                .setTitle(if (retry) "Неверный PIN — попробуйте ещё раз" else "Введите PIN с экрана ТВ")
                .setMessage("На экране телевизора отображён 4-значный PIN-код. Введите его здесь, чтобы разрешить пульту управление ТВ.")
                .setView(input)
                .setPositiveButton("OK") { _, _ -> submit(input.text.toString().trim()) }
                .setNegativeButton("Отмена") { _, _ -> submit(null) }
                .setOnCancelListener { submit(null) }
                .show()
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
                .setTitle("Список приложений")
                .setMessage("ТВ не выдал список приложений.\nПодробности по каждой стратегии — в «Журнале связи с ТВ».")
                .setPositiveButton("Журнал") { _, _ -> openLogDialog() }
                .setNegativeButton("Закрыть", null)
                .show()
            return
        }
        val names = merged.map { "${it.name}\n(${it.appId})" }.toTypedArray()
        val title = when {
            catalog && manual.isNotEmpty() -> "Каталог + ваши приложения (${merged.size}) — запуск проверит установку"
            catalog -> "Каталог приложений (${merged.size}) — запуск проверит установку"
            manual.isNotEmpty() -> "Приложения на ТВ + ваши (${merged.size})"
            else -> "Приложения на ТВ (${merged.size})"
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(names) { _, i ->
                sendOrToast { launchApp(merged[i].appId, merged[i].name) }
            }
            .setNeutralButton("Своё приложение…") { _, _ -> showManualAppDialog() }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    /** IP текущего ТВ (как его пишет кнопка «Подключ.» в prefs «host») */
    private fun currentTvHost(): String {
        return prefs.getString("host", null) ?: ipInput.text.toString().trim()
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
        input.hint = "ID или DIAL-имя, например 1112990019 или YouTube"
        input.inputType = InputType.TYPE_CLASS_TEXT
        val nameInput = EditText(this)
        nameInput.hint = "Название для списка (необязательно)"
        nameInput.inputType = InputType.TYPE_CLASS_TEXT
        val remember = CheckBox(this)
        remember.text = "Запомнить в списке приложений"
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
            .setTitle("Запуск по ID/имени приложения")
            .setView(box)
            .setPositiveButton("Запустить") { _, _ ->
                val v = input.text.toString().trim()
                if (v.isNotEmpty()) {
                    val nm = nameInput.text.toString().trim()
                    sendOrToast { launchApp(v, nm.ifEmpty { null }) }
                    if (remember.isChecked) {
                        rememberManualApp(v, nm.ifEmpty { v })
                        toast("Добавлено в список приложений этого ТВ")
                    }
                }
            }
            .setNegativeButton("Отмена", null)
        if (saved.isNotEmpty()) {
            dlg.setNeutralButton("Забыть свои (${saved.size})") { _, _ ->
                prefs.edit().remove("myapps:${currentTvHost()}").apply()
                toast("Свои приложения забыты — список ТВ без них")
            }
        }
        dlg.show()
    }

    /**
     * Кнопка POWER — умный вкл/выкл:
     *  - сеанс с ТВ активен → выключаем (каскад KEY_POWEROFF → KEY_POWER → KEY_POWERTOGGLE,
     *    т.к. разные прошивки принимают разные ключи; для старых ТВ — KEY_POWER);
     *  - ТВ недоступен → Wake-on-LAN и автоматическое переподключение.
     */
    private fun pressPower() {
        val host = prefs.getString("host", null) ?: ipInput.text.toString().trim()
        if (host.isEmpty()) {
            toast("Сначала выберите или найдите ТВ")
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
        statusView.text = "ТВ не отвечает — включаю по сети…"
        Thread {
            var mac = prefs.getString("mac", null)
            if (mac.isNullOrBlank()) mac = learnMac(host)
            if (mac.isNullOrBlank()) {
                runOnUiThread {
                    statusView.text = "MAC-адрес ТВ неизвестен"
                    toast("MAC неизвестен. Подключитесь к ТВ — MAC сохранится автоматически (или удерживайте POWER для ввода MAC)")
                }
                return@Thread
            }
            runOnUiThread { statusView.text = "Включаю ТВ (Wake-on-LAN → $mac)…" }
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
                    statusView.text = "ТВ проснулся — подключаюсь…"
                    appendLog("✔ ТВ ответил после Wake-on-LAN")
                    connectToHost(host)
                } else {
                    statusView.text = "ТВ не проснулся"
                    appendLog("✖ WoL отправлен ($mac), но ТВ не отвечает 15 с. На ТВ включите: Настройки → Общие → Сеть → Пробуждение по сети")
                    toast("ТВ не проснулся — включите на ТВ «Пробуждение по сети» (Wake-on-Network)")
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
                    runOnUiThread { appendLog("MAC ТВ из ARP-кэша: ${parts[3]}") }
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
            .setTitle("MAC-адрес телевизора")
            .setMessage("Смотрите на ТВ: Настройки → Общие → Сеть → Состояние сети")
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ ->
                prefs.edit().putString("mac", input.text.toString().trim()).apply()
            }
            .setNegativeButton("Отмена", null)
            .show()
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
