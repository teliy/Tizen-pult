package com.teliy.tizenpult

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private var remote: SamsungRemote? = null

    private lateinit var ipInput: EditText
    private lateinit var statusView: TextView

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("tizenpult", Context.MODE_PRIVATE)
        ipInput = findViewById(R.id.ipInput)
        statusView = findViewById(R.id.statusView)

        ipInput.setText(prefs.getString("host", ""))

        findViewById<Button>(R.id.btnConnect).setOnClickListener { connect() }
        findViewById<Button>(R.id.btnSendText).setOnClickListener { sendText() }
        findViewById<Button>(R.id.btnApps).setOnClickListener { openApps() }

        val btnPowerOn = findViewById<Button>(R.id.btnPowerOn)
        btnPowerOn.setOnClickListener { powerOn() }
        // Долгое нажатие — ручной ввод MAC (если REST выдал «Unknown» или закрыт)
        btnPowerOn.setOnLongClickListener {
            askMac()
            true
        }
    }

    /** Кнопки с андроид:tag="KEY_..." в разметке обрабатываются здесь */
    fun onKeyClick(v: View) {
        val key = v.tag as? String ?: return
        sendOrToast { sendKey(key) }
    }

    /** Кнопки запуска приложений с tag = appId */
    fun onAppClick(v: View) {
        val appId = v.tag as? String ?: return
        sendOrToast { launchApp(appId) }
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
        }
    }

    private fun connect() {
        val host = ipInput.text.toString().trim()
        if (host.isEmpty()) {
            toast("Введите IP телевизора")
            return
        }
        prefs.edit().putString("host", host).apply()

        statusView.text = "Подключение к $host..."
        remote?.close()
        remote = SamsungRemote(
            host = host,
            token = prefs.getString("token", null),
            onStatus = { s -> runOnUiThread { statusView.text = s } },
            onToken = { t ->
                prefs.edit().putString("token", t).apply()
            },
            onApps = { apps -> runOnUiThread { showApps(apps) } }
        )
        remote?.connect()
        fetchDeviceInfo(host)
    }

    private fun sendText() {
        val text = findViewById<EditText>(R.id.textInput).text.toString()
        if (text.isEmpty()) {
            toast("Введите текст")
            return
        }
        sendOrToast { sendText(text) }
    }

    private fun openApps() = sendOrToast { requestApps() }

    private fun showApps(apps: List<SamsungRemote.AppInfo>) {
        val names = apps.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Приложения на ТВ")
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

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
