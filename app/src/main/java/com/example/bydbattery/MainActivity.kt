package com.example.bydbattery

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val REQ_BT = 1
        private const val MAX_LOG_LINES = 400
    }

    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private var pollTask: ScheduledFuture<*>? = null

    @Volatile private var elm: Elm327? = null
    private val values = HashMap<String, Double?>()

    private lateinit var statusView: TextView
    private lateinit var headerInput: EditText
    private lateinit var connectBtn: Button
    private lateinit var pollBtn: Button
    private lateinit var verboseBox: CheckBox
    private lateinit var termInput: EditText
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private val valueViews = HashMap<String, TextView>()
    private val rawViews = HashMap<String, TextView>()
    private lateinit var powerView: TextView
    private val logLines = ArrayDeque<String>()

    // ---------------------------------------------------------------- UI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())
        setStatus("Не подключено")
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        statusView = TextView(this).apply { textSize = 16f; setTypeface(typeface, Typeface.BOLD) }
        root.addView(statusView)

        val headerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        headerRow.addView(TextView(this).apply { text = "Адрес BMS (ATSH): " })
        headerInput = EditText(this).apply {
            setText("7E7")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            minEms = 4
        }
        headerRow.addView(headerInput)
        root.addView(headerRow)

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        connectBtn = Button(this).apply { text = "Подключить"; setOnClickListener { onConnectClick() } }
        pollBtn = Button(this).apply { text = "Старт опроса"; isEnabled = false; setOnClickListener { togglePolling() } }
        btnRow.addView(connectBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        btnRow.addView(pollBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(btnRow)

        // Значения
        for (p in PARAMS) {
            val title = TextView(this).apply { text = "${p.title}  [22 ${p.did}]"; textSize = 13f; setPadding(0, dp(10), 0, 0) }
            val value = TextView(this).apply { text = "—"; textSize = 26f; setTypeface(typeface, Typeface.BOLD) }
            val raw = TextView(this).apply { textSize = 11f; typeface = Typeface.MONOSPACE; alpha = 0.6f }
            root.addView(title); root.addView(value); root.addView(raw)
            valueViews[p.key] = value
            rawViews[p.key] = raw
        }
        root.addView(TextView(this).apply { text = "Мощность (U × I)"; textSize = 13f; setPadding(0, dp(10), 0, 0) })
        powerView = TextView(this).apply { text = "—"; textSize = 26f; setTypeface(typeface, Typeface.BOLD) }
        root.addView(powerView)

        // Терминал
        root.addView(TextView(this).apply { text = "Терминал (любая команда ELM327 / UDS)"; setPadding(0, dp(20), 0, 0) })
        val termRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        termInput = EditText(this).apply {
            hint = "например 220005 или ATDPN"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        termRow.addView(termInput, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        termRow.addView(Button(this).apply { text = "Отправить"; setOnClickListener { sendTerminal() } })
        root.addView(termRow)

        val logBtnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        verboseBox = CheckBox(this).apply { text = "Подробный лог" }
        logBtnRow.addView(verboseBox, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        logBtnRow.addView(Button(this).apply { text = "Поделиться"; setOnClickListener { shareLog() } })
        logBtnRow.addView(Button(this).apply { text = "Очистить"; setOnClickListener { logLines.clear(); logView.text = "" } })
        root.addView(logBtnRow)

        logView = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true) }
        logScroll = ScrollView(this).apply { addView(logView) }
        root.addView(logScroll, LinearLayout.LayoutParams(MATCH_PARENT, dp(300)))

        return ScrollView(this).apply { addView(root) }
    }

    private fun setStatus(s: String) = ui.post { statusView.text = s }

    private fun log(s: String) {
        val line = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + "  " + s
        ui.post {
            logLines.addLast(line)
            while (logLines.size > MAX_LOG_LINES) logLines.removeFirst()
            logView.text = logLines.joinToString("\n")
            logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun toast(s: String) = ui.post { Toast.makeText(this, s, Toast.LENGTH_LONG).show() }

    private fun shareLog() {
        val text = logLines.joinToString("\n")
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }, "Отправить лог"))
    }

    // ---------------------------------------------------------------- Bluetooth

    private fun onConnectClick() {
        if (elm != null) { disconnect(); return }
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BT)
            return
        }
        pickDevice()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BT) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) pickDevice()
            else toast("Без разрешения Bluetooth приложение не сможет подключиться к адаптеру")
        }
    }

    @SuppressLint("MissingPermission")
    private fun pickDevice() {
        val adapter: BluetoothAdapter? = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) { toast("На устройстве нет Bluetooth"); return }
        if (!adapter.isEnabled) {
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        val devices = adapter.bondedDevices.toList().sortedByDescending { d ->
            val n = (d.name ?: "").uppercase()
            n.contains("OBD") || n.contains("ELM") || n.contains("V-LINK") || n.contains("VLINK")
        }
        if (devices.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Нет сопряжённых устройств")
                .setMessage("Сначала добавьте адаптер в системных настройках Bluetooth (PIN обычно 1234 или 0000), затем вернитесь в приложение.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val names = devices.map { "${it.name ?: "Без имени"}\n${it.address}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Выберите адаптер ELM327")
            .setItems(names) { _, i -> connect(devices[i]) }
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun openSocket(device: BluetoothDevice): BluetoothSocket {
        val attempts: List<Pair<String, () -> BluetoothSocket>> = listOf(
            "secure SPP" to { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            "insecure SPP" to { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            "канал 1" to {
                device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(device, 1) as BluetoothSocket
            }
        )
        var last: Exception? = null
        for ((name, make) in attempts) {
            var s: BluetoothSocket? = null
            try {
                log("Подключение ($name)…")
                s = make()
                s.connect()
                return s
            } catch (e: Exception) {
                last = e
                log("  не удалось: ${e.message}")
                try { s?.close() } catch (_: Exception) {}
            }
        }
        throw IOException("Не удалось подключиться: ${last?.message}")
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        val header = headerInput.text.toString().trim().uppercase().ifEmpty { "7E7" }
        connectBtn.isEnabled = false
        setStatus("Подключение к ${device.name}…")
        worker.execute {
            try {
                val e = Elm327(openSocket(device))
                elm = e
                log("Bluetooth подключён, инициализация ELM327")
                val init = listOf(
                    "ATZ", "ATE0", "ATL0", "ATH0", "ATSP6", "ATAT1", "ATST96",
                    "ATSH$header", "ATFCSH$header", "ATFCSD300000", "ATFCSM1"
                )
                for (cmd in init) {
                    val r = e.send(cmd, if (cmd == "ATZ") 5000 else 2000)
                    log("$cmd → ${r.replace("\n", " | ")}")
                }
                log("ATDPN → " + e.send("ATDPN"))
                log("Напряжение 12 В: " + e.send("ATRV"))
                setStatus("Подключено: ${device.name}")
                ui.post {
                    connectBtn.text = "Отключить"
                    connectBtn.isEnabled = true
                    pollBtn.isEnabled = true
                }
                startPolling()
            } catch (ex: Exception) {
                log("Ошибка: ${ex.message}")
                setStatus("Ошибка подключения")
                closeQuietly()
                ui.post { connectBtn.isEnabled = true; connectBtn.text = "Подключить" }
            }
        }
    }

    private fun disconnect() {
        stopPolling()
        worker.execute {
            closeQuietly()
            log("Отключено")
            setStatus("Не подключено")
            ui.post {
                connectBtn.text = "Подключить"
                pollBtn.isEnabled = false
            }
        }
    }

    private fun closeQuietly() {
        elm?.close()
        elm = null
    }

    // ---------------------------------------------------------------- Опрос

    private fun togglePolling() {
        if (pollTask == null) startPolling() else stopPolling()
    }

    private fun startPolling() {
        if (pollTask != null) return
        pollTask = worker.scheduleWithFixedDelay({ pollOnce() }, 0, 1000, TimeUnit.MILLISECONDS)
        ui.post { pollBtn.text = "Стоп опроса" }
    }

    private fun stopPolling() {
        pollTask?.cancel(false)
        pollTask = null
        ui.post { pollBtn.text = "Старт опроса" }
    }

    private fun pollOnce() {
        val e = elm ?: return
        try {
            for (p in PARAMS) {
                val raw = e.send("22" + p.did)
                if (verboseBox.isChecked) log("22${p.did} → ${raw.replace("\n", " | ")}")
                when (val r = Uds.parse(raw, p.did)) {
                    is UdsResult.Ok -> {
                        val v = p.decode(r.data)
                        values[p.key] = v
                        ui.post {
                            valueViews[p.key]?.text =
                                if (v == null) "—" else "%.${p.decimals}f %s".format(v, p.unit)
                            rawViews[p.key]?.text = r.rawHex
                        }
                    }
                    is UdsResult.Error -> {
                        values[p.key] = null
                        ui.post {
                            valueViews[p.key]?.text = "—"
                            rawViews[p.key]?.text = r.message
                        }
                    }
                }
            }
            val u = values["volt"]
            val i = values["amp"]
            ui.post {
                powerView.text = if (u != null && i != null) "%.1f кВт".format(u * i / 1000.0) else "—"
            }
        } catch (ex: Exception) {
            log("Связь потеряна: ${ex.message}")
            setStatus("Связь потеряна")
            stopPolling()
            closeQuietly()
            ui.post { connectBtn.text = "Подключить"; pollBtn.isEnabled = false }
        }
    }

    private fun sendTerminal() {
        val cmd = termInput.text.toString().trim().uppercase().replace(" ", "")
        if (cmd.isEmpty()) return
        val e = elm
        if (e == null) { toast("Сначала подключитесь к адаптеру"); return }
        termInput.setText("")
        worker.execute {
            try {
                val r = e.send(cmd, 4000)
                log("> $cmd\n${r}")
            } catch (ex: Exception) {
                log("> $cmd : ошибка ${ex.message}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pollTask?.cancel(false)
        worker.execute { closeQuietly() }
        worker.shutdown()
    }
}
