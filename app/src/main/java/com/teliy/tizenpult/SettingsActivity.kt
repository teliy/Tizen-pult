package com.teliy.tizenpult

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
 *  - оформление: тема — Базовый / Glassmorphism (светлая) / Неоморфизм (светлый);
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

    /** APK, ожидающий установки после выдачи разрешения «неизвестные приложения» */
    private var pendingInstall: File? = null

    private data class TvOption(val label: String, val ip: String)
    private var tvOptions: List<TvOption> = emptyList()
    private var suppressSpin = false

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = getSharedPreferences("tizenpult", Context.MODE_PRIVATE)
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
        findViewById<Button>(R.id.btnConnect).setOnClickListener { connectManual() }
        findViewById<Button>(R.id.btnUpdate).setOnClickListener { checkUpdate() }
        findViewById<Button>(R.id.btnMacSave).setOnClickListener { saveMac() }
        findViewById<TextView>(R.id.versionView).text = "Версия ${currentVersionName()}"

        // Выбор ТВ из истории/поиска — сохраняем сразу, подключение сделает пульт
        tvSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (suppressSpin) return
                val opt = tvOptions.getOrNull(pos) ?: return
                if (opt.ip.isEmpty()) return // пункт-подсказка «ТВ не найден — нажмите Найти»
                prefs.edit().putString("host", opt.ip).apply()
                ipInput.setText(opt.ip)
                showSavedHost()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Тема: тап по строке — сохранить и применить к настройкам
        findViewById<View>(R.id.themeOptBasic).setOnClickListener { pickTheme(ThemeEngine.BASIC) }
        findViewById<View>(R.id.themeOptGlass).setOnClickListener { pickTheme(ThemeEngine.GLASS) }
        findViewById<View>(R.id.themeOptNeu).setOnClickListener { pickTheme(ThemeEngine.NEU) }
        renderThemeSelection()

        refreshTvSpinner(prefs.getString("host", null))
    }

    // ---------- Телевизор ----------

    private fun showSavedHost() {
        val host = prefs.getString("host", null)
        val name = TvStore.load(prefs).firstOrNull { it.ip == host }?.name
        statusView.text = when {
            host.isNullOrBlank() -> "ТВ не выбран — нажмите «Найти ТВ в сети»"
            name.isNullOrBlank() -> "ТВ: $host"
            else -> "ТВ: $name — $host"
        }
    }

    /**
     * Поиск ТВ: сохранённый адрес + прошлые находки + SSDP + скан всех подсетей.
     * Найденное попадает в общую историю (TvStore) и в выпадающий список.
     */
    @SuppressLint("SetTextI18n")
    private fun findTv() {
        statusView.text = "Поиск ТВ в сети…"
        Thread {
            val report = TvDiscovery.discoverAll(
                applicationContext,
                prefs.getString("host", null),
                TvStore.load(prefs).map { it.ip }
            ) { p -> runOnUiThread { statusView.text = "🔎 $p" } }

            TvStore.rememberFound(prefs, report.found)

            runOnUiThread {
                when {
                    report.found.isEmpty() ->
                        statusView.text =
                            "ТВ не найден. Включите ТВ, проверьте Wi-Fi или введите IP вручную"
                    report.found.size == 1 -> {
                        prefs.edit().putString("host", report.found[0].ip).apply()
                        ipInput.setText(report.found[0].ip)
                        statusView.text =
                            "Найден ТВ: ${report.found[0].name} (${report.found[0].ip}) — сохранён"
                        refreshTvSpinner(report.found[0].ip)
                    }
                    else -> {
                        statusView.text =
                            "Найдено ТВ: ${report.found.size} — выберите в списке и нажмите «Подключ.»"
                        refreshTvSpinner(prefs.getString("host", null))
                    }
                }
            }
        }.start()
    }

    /** Сохранить IP вручную; подключение выполнит главный экран при возврате */
    private fun connectManual() {
        val host = ipInput.text.toString().trim()
        if (host.isEmpty()) {
            toast("Введите IP или нажмите «Найти ТВ»")
            return
        }
        prefs.edit().putString("host", host).apply()
        showSavedHost()
        statusView.text = "ТВ сохранён: $host — вернитесь назад, пульт подключится"
        toast("Сохранено — вернитесь к пульту")
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
                val old = if (k.legacy) " — старый протокол" else ""
                opts.add(TvOption("$mark${k.name} — ${k.model.ifBlank { "Samsung" }} — ${k.ip}$old", k.ip))
            }
        }
        if (opts.isEmpty()) {
            opts.add(TvOption("ТВ не найден — нажмите «Найти ТВ в сети»", ""))
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
        toast("Тема применена")
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
        row(R.id.dotNeu, R.id.nameNeu, ThemeEngine.NEU, selected.neu)
    }

    private data class ColorSelected(val basic: Int, val glass: Int, val neu: Int)

    // ---------- Приложение ----------

    private fun saveMac() {
        val mac = findViewById<EditText>(R.id.macInput).text.toString().trim()
        if (mac.isNotEmpty() && !mac.contains(":")) {
            toast("MAC выглядит странно — нужен вид C0:23:8D:49:B3:BC")
            return
        }
        prefs.edit().putString("mac", mac).apply()
        toast(if (mac.isEmpty()) "MAC очищен" else "MAC сохранён: $mac")
    }

    private fun currentVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    /** Проверка обновлений: GitHub Releases → сравнение версий → диалог */
    @SuppressLint("SetTextI18n")
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

    /** Скачивание APK с прогрессом в строке статуса настроек */
    @SuppressLint("SetTextI18n")
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

    override fun onResume() {
        super.onResume()
        // Возврат из системных настроек «установка неизвестных приложений»
        val f = pendingInstall ?: return
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) return
        pendingInstall = null
        if (f.exists()) startInstall(f)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
