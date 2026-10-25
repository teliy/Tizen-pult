package com.teliy.tizenpult

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

/**
 * v3.1: экран «Настройки» — всё, что не кнопки пульта:
 *  - телевизор: поиск в сети (история + SSDP + скан подсети), выбор из списка,
 *    IP вручную, сохранение выбранного ТВ (подключение выполняет пульт);
 *  - оформление: тема — Базовый / Glassmorphism (светлая и тёмная) /
 *    Неоморфизм (светлый и тёмный);
 *  - приложение: MAC ТВ для Wake-on-LAN, проверка обновлений, версия.
 *
 * Экран не держит собственного сеанса с ТВ: выбор сохраняется в prefs «host»,
 * MainActivity при возврате замечает смену и подключается сам.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var statusView: TextView
    private lateinit var tvSpinner: Spinner
    private lateinit var ipInput: EditText
    private lateinit var langSpinner: Spinner // v3.6

    /** APK, ожидающий установки после выдачи разрешения «неизвестные приложения» */
    private var pendingInstall: File? = null

    private data class TvOption(val label: String, val ip: String)
    private var tvOptions: List<TvOption> = emptyList()
    private var suppressSpin = false

    /** v3.6: подавление срабатывания спиннера языка при программной установке */
    private var suppressLang = false

    /** v3.6: язык, выбранный в настройках, — применяется к ресурсам активити */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(L10n.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = getSharedPreferences("tizenpult", Context.MODE_PRIVATE)
        L10n.init(applicationContext) // v3.5: строки вне активити
        ThemeEngine.applyTheme(this, prefs)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        ThemeEngine.styleRoot(this)

        statusView = findViewById(R.id.settingsStatus)
        tvSpinner = findViewById(R.id.tvSpinner)
        ipInput = findViewById(R.id.ipInput)

        ipInput.setText(prefs.getString("host", ""))
        showSavedHost()

        findViewById<Button>(R.id.btnFind).setOnClickListener { findTv() }
        findViewById<Button>(R.id.btnRename).setOnClickListener { renameTv() } // v3.3
        findViewById<Button>(R.id.btnConnect).setOnClickListener { connectManual() }
        findViewById<Button>(R.id.btnUpdate).setOnClickListener { checkUpdate() }
        findViewById<Button>(R.id.btnMacSave).setOnClickListener { saveMac() }
        findViewById<TextView>(R.id.versionView).text = getString(R.string.version_fmt, currentVersionName())
        langSpinner = findViewById(R.id.langSpinner) // v3.6
        setupLangSpinner()

        // Выбор ТВ из истории/поиска — сохраняем сразу, подключение сделает пульт
        tvSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (suppressSpin) return
                val opt = tvOptions.getOrNull(pos) ?: return
                if (opt.ip.isEmpty()) return // пункт-подсказка «ТВ не найден — нажмите Найти»
                prefs.edit().putString("host", opt.ip).apply()
                ipInput.setText(opt.ip)
                showSavedHost()
                maybePairNewTv(opt.ip) // v3.5: новый ТВ — сопряжение прямо здесь
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Тема: тап по строке — сохранить и применить к настройкам
        findViewById<View>(R.id.themeOptBasic).setOnClickListener { pickTheme(ThemeEngine.BASIC) }
        findViewById<View>(R.id.themeOptGlass).setOnClickListener { pickTheme(ThemeEngine.GLASS) }
        findViewById<View>(R.id.themeOptGlassDark).setOnClickListener { pickTheme(ThemeEngine.GLASS_DARK) }
        findViewById<View>(R.id.themeOptNeu).setOnClickListener { pickTheme(ThemeEngine.NEU) }
        findViewById<View>(R.id.themeOptNeuDark).setOnClickListener { pickTheme(ThemeEngine.NEU_DARK) }
        renderThemeSelection()

        refreshTvSpinner(prefs.getString("host", null))
    }

    // ---------- Телевизор ----------

    private fun showSavedHost() {
        val host = prefs.getString("host", null)
        val name = TvStore.load(prefs).firstOrNull { it.ip == host }?.name
        statusView.text = when {
            host.isNullOrBlank() -> getString(R.string.s_no_tv)
            name.isNullOrBlank() -> getString(R.string.s_tv_fmt, host)
            else -> getString(R.string.s_tv_named, name, host)
        }
    }

    /**
     * Поиск ТВ: сохранённый адрес + прошлые находки + SSDP + скан всех подсетей.
     * Найденное попадает в общую историю (TvStore) и в выпадающий список.
     */
    @SuppressLint("SetTextI18n")
    private fun findTv() {
        statusView.text = getString(R.string.search_tv)
        Thread {
            val report = TvDiscovery.discoverAll(
                applicationContext,
                prefs.getString("host", null),
                TvStore.load(prefs).map { it.ip }
            ) { p -> runOnUiThread { statusView.text = getString(R.string.s_progress, p) } }

            TvStore.rememberFound(prefs, report.found)

            runOnUiThread {
                when {
                    report.found.isEmpty() ->
                        statusView.text =
                            getString(R.string.s_not_found)
                    report.found.size == 1 -> {
                        prefs.edit().putString("host", report.found[0].ip).apply()
                        ipInput.setText(report.found[0].ip)
                        statusView.text =
                            getString(R.string.s_found_one, report.found[0].name, report.found[0].ip)
                        refreshTvSpinner(report.found[0].ip)
                        maybePairNewTv(report.found[0].ip) // v3.5: сразу сопряжение
                    }
                    else -> {
                        statusView.text =
                            getString(R.string.s_found_n, report.found.size)
                        refreshTvSpinner(prefs.getString("host", null))
                    }
                }
            }
        }.start()
    }

    /**
     * v3.3: переименование выбранного ТВ — имя сохраняется в истории (TvStore)
     * и видно в списке на пульте и в настройках. Если ТВ ещё не в истории
     * (только что ввели IP вручную) — добавляем запись с этим именем.
     */
    private fun renameTv() {
        val host = prefs.getString("host", null)
        if (host.isNullOrBlank()) {
            toast(getString(R.string.s_need_tv))
            return
        }
        val list = TvStore.load(prefs)
        val entry = list.firstOrNull { it.ip == host }
        val current = entry?.name?.takeIf { it.isNotBlank() } ?: host
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
            setText(current)
            setSelection(current.length)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.s_rename_title))
            .setMessage(getString(R.string.s_rename_msg, host))
            .setView(input)
            .setPositiveButton(getString(R.string.gen_save)) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty()) {
                    toast(getString(R.string.s_name_empty))
                    return@setPositiveButton
                }
                val i = list.indexOfFirst { it.ip == host }
                if (i >= 0) list[i] = list[i].copy(name = newName)
                else list.add(
                    0,
                    TvStore.KnownTv(
                        ip = host, name = newName, model = "",
                        lastSeen = System.currentTimeMillis()
                    )
                )
                TvStore.save(prefs, list)
                refreshTvSpinner(host)
                showSavedHost()
                toast(getString(R.string.s_renamed, newName))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Сохранить IP вручную и сразу проверить подключение (новый ТВ — сопряжение здесь) */
    private fun connectManual() {
        val host = ipInput.text.toString().trim()
        if (host.isEmpty()) {
            toast(getString(R.string.s_enter_ip))
            return
        }
        prefs.edit().putString("host", host).apply()
        showSavedHost()
        toast(getString(R.string.s_saved))
        maybePairNewTv(host) // v3.5: не просто сохранить, а сразу подключить
    }

    /**
     * Пересборка выпадающего списка ТВ: история поисков (и Tizen, и старые),
     * текущий отмечается «▸».
     */
    private fun refreshTvSpinner(selectIp: String? = null) {
        val opts = mutableListOf<TvOption>()
        val seen = mutableSetOf<String>()
        for (k in TvStore.load(prefs)) {
            if (seen.add(k.ip)) {
                val mark = if (selectIp == k.ip) "▸ " else ""
                val old = if (k.legacy) getString(R.string.s_suffix_legacy) else ""
                opts.add(TvOption("$mark${k.name} — ${k.model.ifBlank { "Samsung" }} — ${k.ip}$old", k.ip))
            }
        }
        if (opts.isEmpty()) {
            opts.add(TvOption(getString(R.string.s_spinner_hint), ""))
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

    // ---------- Оформление ----------

    private fun pickTheme(theme: String) {
        if (ThemeEngine.current(prefs) == theme) return
        ThemeEngine.save(prefs, theme)
        toast(getString(R.string.s_theme_applied))
        recreate()
    }

    /** Точки-индикаторы и жирность названий у выбранной темы */
    private fun renderThemeSelection() {
        val cur = ThemeEngine.current(prefs)
        val selected = ColorSelected(0xFF0B7A75.toInt(), 0xFF5B6CF0.toInt(), 0xFF576078.toInt())
        val idle = 0xFFB0B4C4.toInt()

        fun row(dotId: Int, nameId: Int, theme: String, accent: Int) {
            val isSel = cur == theme
            findViewById<TextView>(dotId).setTextColor(if (isSel) accent else idle)
            findViewById<TextView>(nameId).alpha = if (isSel) 1f else 0.72f
        }
        row(R.id.dotBasic, R.id.nameBasic, ThemeEngine.BASIC, selected.basic)
        row(R.id.dotGlass, R.id.nameGlass, ThemeEngine.GLASS, selected.glass)
        row(R.id.dotGlassDark, R.id.nameGlassDark, ThemeEngine.GLASS_DARK, selected.glass)
        row(R.id.dotNeu, R.id.nameNeu, ThemeEngine.NEU, selected.neu)
        row(R.id.dotNeuDark, R.id.nameNeuDark, ThemeEngine.NEU_DARK, selected.neu)
    }

    private data class ColorSelected(val basic: Int, val glass: Int, val neu: Int)

    // ---------- Язык (v3.6) ----------

    /**
     * Спиннер языка: пункты — названия языков на самих языках (English / Русский / …),
     * первый пункт — «Системный» (переводится). Выбор сохраняется в prefs «lang»
     * и пересобирает настройки сразу; пульт обновится при возврате (onResume).
     */
    private fun setupLangSpinner() {
        val entries = resources.getStringArray(R.array.lang_entries)
        val values = resources.getStringArray(R.array.lang_values)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, entries)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        langSpinner.adapter = adapter
        val cur = prefs.getString("lang", "sys") ?: "sys"
        suppressLang = true
        langSpinner.setSelection(values.indexOf(cur).coerceAtLeast(0))
        // onItemSelected приходит после отрисовки — снимаем блокировку позже
        langSpinner.post { suppressLang = false }
        langSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (suppressLang) return
                val code = values.getOrNull(pos) ?: return
                if (code == (prefs.getString("lang", "sys") ?: "sys")) return
                prefs.edit().putString("lang", code).apply()
                toast(getString(R.string.lang_applied))
                recreate() // настройки сразу на выбранном языке
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // ---------- Приложение ----------

    private fun saveMac() {
        val mac = findViewById<EditText>(R.id.macInput).text.toString().trim()
        if (mac.isNotEmpty() && !mac.contains(":")) {
            toast(getString(R.string.s_mac_weird))
            return
        }
        prefs.edit().putString("mac", mac).apply()
        toast(if (mac.isEmpty()) getString(R.string.s_mac_cleared) else getString(R.string.s_mac_saved, mac))
    }

    private fun currentVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    /** Проверка обновлений: GitHub Releases → сравнение версий → диалог */
    @SuppressLint("SetTextI18n")
    private fun checkUpdate() {
        statusView.text = getString(R.string.s_upd_checking)
        Thread {
            try {
                val rel = UpdateChecker.fetchLatest()
                val cur = currentVersionName()
                if (!UpdateChecker.isNewer(rel.version, cur)) {
                    runOnUiThread {
                        statusView.text = getString(R.string.s_upd_none)
                        AlertDialog.Builder(this)
                            .setTitle(getString(R.string.s_upd_uptodate_title))
                            .setMessage(getString(R.string.s_upd_uptodate_msg, cur, rel.tag))
                            .setPositiveButton("OK", null)
                            .show()
                    }
                    return@Thread
                }
                if (rel.apkUrl.isNullOrBlank()) {
                    runOnUiThread {
                        statusView.text = getString(R.string.s_upd_no_apk, rel.tag)
                        toast(getString(R.string.s_upd_no_apk, rel.tag))
                    }
                    return@Thread
                }
                val url = rel.apkUrl
                val mb = rel.apkSize / 1048576f
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.s_upd_available, rel.version))
                        .setMessage(
                            getString(R.string.s_upd_msg, cur, rel.version, mb)
                        )
                        .setPositiveButton(getString(R.string.s_download_btn)) { _, _ -> downloadAndInstall(url, rel.version) }
                        .setNegativeButton(getString(R.string.gen_later), null)
                        .show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusView.text = getString(R.string.s_upd_check_error)
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.s_upd_check_fail_title))
                        .setMessage(getString(R.string.s_upd_fail_msg, e.message))
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }.start()
    }

    /** Скачивание APK с прогрессом в строке статуса настроек */
    @SuppressLint("SetTextI18n")
    private fun downloadAndInstall(url: String, version: String) {
        statusView.text = getString(R.string.s_downloading, version)
        Thread {
            try {
                val dest = File(getExternalFilesDir(null), "update-$version.apk")
                UpdateChecker.download(url, dest) { done, total ->
                    val mb = 1048576f
                    val txt = if (total > 0)
                        getString(R.string.s_downloading_progress, version, done / mb, total / mb)
                    else
                        getString(R.string.s_downloading_mb, version, done / mb)
                    runOnUiThread { statusView.text = txt }
                }
                pendingInstall = dest
                runOnUiThread {
                    statusView.text = getString(R.string.s_downloaded, dest.name)
                    startInstall(dest)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusView.text = getString(R.string.s_download_error, e.message)
                    toast(getString(R.string.s_download_error, e.message))
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
            toast(getString(R.string.s_allow_install, getString(R.string.app_name)))
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                )
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
                } catch (e: Exception) {
                    toast(getString(R.string.s_allow_install_manual, getString(R.string.app_name)))
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
            toast(getString(R.string.s_installer_fail, e.message))
        }
    }

    override fun onResume() {
        super.onResume()
        // Возврат из системных настроек «установка неизвестных приложений»
        val f = pendingInstall ?: return
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) return
        pendingInstall = null
        if (f.exists()) startInstall(f)
    }

    override fun onDestroy() {
        super.onDestroy()
        closePairing() // v3.5: не оставлять сеанс с ТВ после закрытия настроек
    }

    // ---------- v3.5: сопряжение нового ТВ прямо в настройках ----------

    /** Активная проба подключения (закрывается при уходе с экрана) */
    private var pairingRemote: SamsungRemote? = null

    /** Номер текущей попытки: устаревшие фоновые потоки не трогают UI */
    @Volatile private var pairingSeq = 0

    /**
     * Если этот ТВ ещё ни разу не подключался (нет сохранённого протокола) —
     * подключаемся прямо отсюда: при первом сопряжении ТВ покажет PIN, и окно
     * ввода откроется в настройках, а не на пульте. Успешное сопряжение
     * запоминает протокол и токен — пульт после возврата подключится молча.
     */
    private fun maybePairNewTv(host: String) {
        if (host.isBlank()) return
        if (!prefs.getString("proto:$host", null).isNullOrBlank()) return // уже работали — PIN не нужен
        closePairing()
        val seq = ++pairingSeq
        statusView.text =
            getString(R.string.s_pair_start, host)
        Thread {
            val ok = try {
                pairProbe(host)
            } finally {
                if (seq == pairingSeq) pairingRemote = null
            }
            runOnUiThread {
                if (seq != pairingSeq) return@runOnUiThread // уже начали другую попытку
                statusView.text = if (ok)
                    getString(R.string.s_pair_ok)
                else
                    getString(R.string.s_pair_fail)
            }
        }.start()
    }

    private fun closePairing() {
        pairingSeq++ // фоновые потоки прошлой попытки перестают трогать UI
        pairingRemote?.close()
        pairingRemote = null
    }

    /** Перебор протоколов — как в пульте (порты → порядок проб) */
    private fun pairProbe(host: String): Boolean {
        val p8002 = TvDiscovery.portOpen(host, 8002, 1500)
        val p8001 = TvDiscovery.portOpen(host, 8001, 1500)
        val p55000 = TvDiscovery.portOpen(host, 55000, 1500)
        var p8080 = false
        var p8000 = false
        if (!p8002) {
            p8080 = TvDiscovery.portOpen(host, 8080, 1200)
            p8000 = TvDiscovery.portOpen(host, 8000, 1200)
        }
        val caps = mutableListOf<String>()
        if (p8002) caps.add("wss")
        if (p8001) caps.add("ws")
        caps.add("legacy")
        caps.add("legacy-aes")
        if (p8080 || p8000) caps.add("smart")
        val saved = prefs.getString("proto:$host", null)
        if (!saved.isNullOrBlank() && caps.remove(saved)) caps.add(0, saved)
        for (proto in caps) {
            if (pairTryProto(host, proto)) {
                prefs.edit().putString("proto:$host", proto).apply()
                return true
            }
        }
        return false
    }

    /** Одна попытка сопряжения; PIN запрашивается диалогом в настройках */
    private fun pairTryProto(host: String, proto: String): Boolean {
        val seq = pairingSeq
        val r = SamsungRemote(
            host = host,
            proto = proto,
            token = prefs.getString("token:$host", null),
            smartAuth = prefs.getString("sv:$host", null),
            deviceId = deviceUuid(),
            onStatus = { s -> runOnUiThread { if (seq == pairingSeq) statusView.text = s } },
            onToken = { t -> prefs.edit().putString("token:$host", t).apply() },
            onApps = { _, _ -> },
            onLog = { }, // подробный журнал — на пульте, здесь только статус
            onSmartAuth = { auth -> prefs.edit().putString("sv:$host", auth).apply() },
            onPin = { retry, submit -> showPinDialog(retry, submit) }
        )
        pairingRemote = r
        r.connect()
        // smart: сопряжение с вводом PIN занимает время — даём до 5 минут
        val deadAfter = if (proto == "smart") 300_000L else 8_000L
        val aliveAfter = if (proto == "smart") 330_000L else 30_000L
        val t0 = System.currentTimeMillis()
        while (seq == pairingSeq) {
            if (r.isRejected()) return false
            if (r.isConnected()) {
                r.close() // сопряжение завершено — пульт переподключится сам по токену
                return true
            }
            val elapsed = System.currentTimeMillis() - t0
            if (r.isTransportAlive()) {
                if (elapsed > aliveAfter) break
            } else if (elapsed > deadAfter) {
                break
            }
            Thread.sleep(250)
        }
        r.close()
        return false
    }

    /** Стабильный UUID устройства для сопряжения Smart View 2.0 (тот же, что у пульта) */
    private fun deviceUuid(): String {
        var id = prefs.getString("svdevid", null)
        if (id.isNullOrBlank()) {
            id = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("svdevid", id).apply()
        }
        return id
    }

    /** v3.5: диалог ввода PIN с экрана ТВ — прямо в настройках */
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

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
