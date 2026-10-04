package com.example.bydbattery

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
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
import java.io.File
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
        private const val CELL_NOMINAL_V = 3.2 // LFP
    }

    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private var pollTask: ScheduledFuture<*>? = null

    @Volatile private var elm: Elm327? = null
    @Volatile private var header = "7E7"
    @Volatile private var cellCount = 90
    @Volatile private var verbose = false
    @Volatile private var scanning = false
    @Volatile private var scanStop = false
    private var lastScanFile: File? = null

    private val values = HashMap<String, Double?>()

    // Счётчики энергии (интегрирование мощности между опросами)
    @Volatile private var energyIn = 0.0   // кВт·ч, получено батареей
    @Volatile private var energyOut = 0.0  // кВт·ч, отдано батареей
    private var lastPollMs = 0L

    private lateinit var statusView: TextView
    private lateinit var headerInput: EditText
    private lateinit var cellsInput: EditText
    private lateinit var connectBtn: Button
    private lateinit var pollBtn: Button
    private lateinit var scanBtn: Button
    private lateinit var termInput: EditText
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private val valueViews = HashMap<String, TextView>()
    private val rawViews = HashMap<String, TextView>()
    private val logLines = ArrayDeque<String>()

    // ---------------------------------------------------------------- UI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())
        setStatus("Не подключено")
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun section(root: LinearLayout, title: String) {
        root.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(22), 0, dp(2))
        })
    }

    private fun valueRow(root: LinearLayout, key: String, title: String, withRaw: Boolean) {
        root.addView(TextView(this).apply { text = title; textSize = 13f; setPadding(0, dp(10), 0, 0) })
        val value = TextView(this).apply { text = "—"; textSize = 24f; setTypeface(typeface, Typeface.BOLD) }
        root.addView(value)
        valueViews[key] = value
        if (withRaw) {
            val raw = TextView(this).apply { textSize = 11f; typeface = Typeface.MONOSPACE; alpha = 0.6f }
            root.addView(raw)
            rawViews[key] = raw
        }
    }

    private fun watch(edit: EditText, onChange: (String) -> Unit) {
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { onChange(s?.toString() ?: "") }
        })
    }

    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        statusView = TextView(this).apply { textSize = 16f; setTypeface(typeface, Typeface.BOLD) }
        root.addView(statusView)

        val settingsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        settingsRow.addView(TextView(this).apply { text = "BMS: " })
        headerInput = EditText(this).apply {
            setText(header)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            minEms = 3
        }
        settingsRow.addView(headerInput)
        settingsRow.addView(TextView(this).apply { text = "   Ячеек: " })
        cellsInput = EditText(this).apply {
            setText(cellCount.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            minEms = 2
        }
        settingsRow.addView(cellsInput)
        root.addView(settingsRow)
        watch(headerInput) { header = it.trim().uppercase().ifEmpty { "7E7" } }
        watch(cellsInput) { cellCount = it.trim().toIntOrNull()?.takeIf { n -> n in 1..400 } ?: 90 }

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        connectBtn = Button(this).apply { text = "Подключить"; setOnClickListener { onConnectClick() } }
        pollBtn = Button(this).apply { text = "Старт опроса"; isEnabled = false; setOnClickListener { togglePolling() } }
        btnRow.addView(connectBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        btnRow.addView(pollBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(btnRow)

        // --- Данные от BMS
        section(root, "Данные BMS")
        for (p in PARAMS) valueRow(root, p.key, "${p.title}  [22 ${p.did}]", true)

        // --- Вычисляемые
        section(root, "Вычисляемые")
        valueRow(root, "power", "Мощность (U × I)", false)
        valueRow(root, "mode", "Режим (по знаку тока, проверьте на зарядке)", false)
        valueRow(root, "crate", "C-rate (ток / ёмкость)", false)
        valueRow(root, "soh", "SOH ≈ фактическая / номинальная ёмкость (гипотеза)", false)
        valueRow(root, "e_full", "Запас энергии при 100 % (ёмкость × ячеек × 3,2 В)", false)
        valueRow(root, "e_left", "Осталось энергии", false)
        valueRow(root, "e_in", "Получено батареей с момента сброса", false)
        valueRow(root, "e_out", "Отдано батареей с момента сброса", false)
        root.addView(Button(this).apply {
            text = "Сбросить счётчики энергии"
            setOnClickListener { energyIn = 0.0; energyOut = 0.0; renderEnergy() }
        })

        // --- Сканер
        section(root, "Сканер BMS")
        root.addView(TextView(this).apply {
            textSize = 13f
            text = "Перебирает идентификаторы (только чтение, сервис 22) по адресу BMS и сохраняет все ответы в файл. " +
                "Зажигание должно быть включено. Полезно сделать два скана: в покое и во время зарядки."
        })
        val scanRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        scanBtn = Button(this).apply { text = "Запустить скан"; setOnClickListener { onScanClick() } }
        scanRow.addView(scanBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        scanRow.addView(Button(this).apply {
            text = "Отправить файл"
            setOnClickListener { lastScanFile?.let { shareFile(it) } ?: toast("Скан ещё не выполнялся") }
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(scanRow)

        // --- Терминал и лог
        section(root, "Терминал")
        val termRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        termInput = EditText(this).apply {
            hint = "например 220005 или ATDPN"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        termRow.addView(termInput, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        termRow.addView(Button(this).apply { text = "Отправить"; setOnClickListener { sendTerminal() } })
        root.addView(termRow)

        val logBtnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val verboseBox = CheckBox(this).apply {
            text = "Подробный лог"
            setOnCheckedChangeListener { _, checked -> verbose = checked }
        }
        logBtnRow.addView(verboseBox, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        logBtnRow.addView(Button(this).apply { text = "Поделиться"; setOnClickListener { shareLog() } })
        logBtnRow.addView(Button(this).apply { text = "Очистить"; setOnClickListener { logLines.clear(); logView.text = "" } })
        root.addView(logBtnRow)

        logView = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true) }
        logScroll = ScrollView(this).apply { addView(logView) }
        root.addView(logScroll, LinearLayout.LayoutParams(MATCH_PARENT, dp(300)))

        return ScrollView(this).apply { addView(root) }
    }

    private fun setStatus(s: String) { ui.post { statusView.text = s } }

    private fun setValue(key: String, text: String) { ui.post { valueViews[key]?.text = text } }

    private fun log(s: String) {
        val line = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + "  " + s
        ui.post {
            logLines.addLast(line)
            while (logLines.size > MAX_LOG_LINES) logLines.removeFirst()
            logView.text = logLines.joinToString("\n")
            logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun toast(s: String) { ui.post { Toast.makeText(this, s, Toast.LENGTH_LONG).show() } }

    private fun shareLog() {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, logLines.joinToString("\n"))
        }, "Отправить лог"))
    }

    private fun shareFile(file: File) {
        val uri = ScanFileProvider.uriFor(file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Отправить ${file.name}"))
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
        val h = header
        val name = device.name ?: device.address
        connectBtn.isEnabled = false
        setStatus("Подключение к $name…")
        worker.execute {
            try {
                val e = Elm327(openSocket(device))
                elm = e
                log("Bluetooth подключён, инициализация ELM327")
                val init = listOf(
                    "ATZ", "ATE0", "ATL0", "ATH0", "ATSP6", "ATAT1", "ATST96",
                    "ATSH$h", "ATFCSH$h", "ATFCSD300000", "ATFCSM1"
                )
                for (cmd in init) {
                    val r = e.send(cmd, if (cmd == "ATZ") 5000 else 2000)
                    log("$cmd → ${r.replace("\n", " | ")}")
                }
                log("ATDPN → " + e.send("ATDPN"))
                log("Напряжение 12 В: " + e.send("ATRV"))
                setStatus("Подключено: $name")
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
        scanStop = true
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

    private fun onConnectionLost(ex: Exception) {
        log("Связь потеряна: ${ex.message}")
        setStatus("Связь потеряна")
        stopPolling()
        closeQuietly()
        ui.post { connectBtn.text = "Подключить"; connectBtn.isEnabled = true; pollBtn.isEnabled = false }
    }

    // ---------------------------------------------------------------- Опрос

    private fun togglePolling() {
        if (pollTask == null) startPolling() else stopPolling()
    }

    private fun startPolling() {
        if (pollTask != null || scanning) return
        lastPollMs = 0L
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
        if (scanning) return
        try {
            // один запрос на идентификатор, даже если из него читается несколько параметров
            val responses = LinkedHashMap<String, UdsResult>()
            for (did in PARAMS.map { it.did }.distinct()) {
                val raw = e.send("22$did")
                if (verbose) log("22$did → ${raw.replace("\n", " | ")}")
                responses[did] = Uds.parse(raw, did)
            }
            for (p in PARAMS) {
                when (val r = responses[p.did]) {
                    is UdsResult.Ok -> {
                        val v = p.decode(r.data)
                        values[p.key] = v
                        setValue(p.key, if (v == null) "—" else "%.${p.decimals}f %s".format(v, p.unit).trim())
                        ui.post { rawViews[p.key]?.text = r.rawHex }
                    }
                    is UdsResult.Error -> {
                        values[p.key] = null
                        setValue(p.key, "—")
                        ui.post { rawViews[p.key]?.text = r.message }
                    }
                    null -> {}
                }
            }
            updateDerived()
        } catch (ex: Exception) {
            onConnectionLost(ex)
        }
    }

    private fun fmt(v: Double?, pattern: String) = if (v == null) "—" else pattern.format(v)

    private fun updateDerived() {
        val now = System.currentTimeMillis()
        val soc = values["soc"] ?: values["soc_d"]
        val capAct = values["cap_act"]
        val capNom = values["cap"]
        val cap = capAct ?: capNom
        val u = values["volt"]
        val i = values["amp"]

        val power = if (u != null && i != null) u * i / 1000.0 else null
        setValue("power", fmt(power, "%.2f кВт"))

        setValue("mode", when {
            i == null -> "—"
            i > 0.5 -> "разряд (предположительно)"
            i < -0.5 -> "заряд (предположительно)"
            else -> "покой"
        })

        setValue("crate", if (i != null && cap != null && cap > 0) "%.3f C".format(i / cap) else "—")

        val soh = if (capAct != null && capNom != null && capNom > 0) capAct / capNom * 100 else null
        setValue("soh", fmt(soh, "%.1f %%"))

        val eFull = cap?.let { it * cellCount * CELL_NOMINAL_V / 1000.0 }
        setValue("e_full", fmt(eFull, "%.1f кВт·ч"))
        val eLeft = if (eFull != null && soc != null) eFull * soc / 100.0 else null
        setValue("e_left", fmt(eLeft, "%.1f кВт·ч"))

        if (power != null && lastPollMs > 0) {
            val dtMs = now - lastPollMs
            if (dtMs in 1..10_000) { // большие паузы (скан, обрыв связи) не считаем
                val kwh = power * dtMs / 3_600_000.0
                if (kwh >= 0) energyOut += kwh else energyIn += -kwh
            }
        }
        lastPollMs = now
        renderEnergy()
    }

    private fun renderEnergy() {
        setValue("e_in", "%.3f кВт·ч".format(energyIn))
        setValue("e_out", "%.3f кВт·ч".format(energyOut))
    }

    // ---------------------------------------------------------------- Сканер

    private fun onScanClick() {
        if (scanning) {
            scanStop = true
            scanBtn.text = "Останавливаю…"
            return
        }
        if (elm == null) { toast("Сначала подключитесь к адаптеру"); return }
        val options = arrayOf(
            "Быстрый: 0000–00FF, 1F00–1FFF, F180–F1FF (~640 запросов, 2–5 мин)",
            "Полный: 0000–0FFF, 1F00–1FFF, F180–F1FF (~4 500 запросов, 10–25 мин)"
        )
        AlertDialog.Builder(this)
            .setTitle("Сканер BMS (адрес $header)")
            .setItems(options) { _, which -> startScan(which == 1) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun startScan(full: Boolean) {
        val ranges = if (full) listOf(0x0000..0x0FFF, 0x1F00..0x1FFF, 0xF180..0xF1FF)
                     else listOf(0x0000..0x00FF, 0x1F00..0x1FFF, 0xF180..0xF1FF)
        val total = ranges.sumOf { it.count() }
        stopPolling()
        scanStop = false
        scanning = true
        scanBtn.text = "Остановить скан"
        val snapshot = PARAMS.joinToString(", ") { p -> "${p.key}=${values[p.key]?.let { "%.2f".format(it) } ?: "?"}" }

        worker.execute {
            val e = elm
            if (e == null) { finishScan(null); return@execute }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val out = StringBuilder()
            out.append("# BYD Battery scan $stamp\n")
            out.append("# header=$header mode=${if (full) "full" else "quick"} cells=$cellCount\n")
            out.append("# snapshot: $snapshot\n")
            out.append("# формат: DID  OK len=N : байты данных после 62 XX XX\n")
            var done = 0
            var hits = 0
            var noReply = 0
            val started = System.currentTimeMillis()
            var lost: Exception? = null
            try {
                outer@ for (range in ranges) {
                    for (did in range) {
                        if (scanStop) break@outer
                        val didHex = "%04X".format(did)
                        val raw = e.send("22$didHex", 2500)
                        when (val r = Uds.parse(raw, didHex)) {
                            is UdsResult.Ok -> {
                                hits++
                                val bytes = r.data.joinToString(" ") { "%02X".format(it) }
                                out.append("$didHex OK len=${r.data.size} : $bytes${Hints.of(r.data)}\n")
                                log("✓ 22$didHex (${r.data.size} байт)")
                            }
                            is UdsResult.Error -> when {
                                r.nrc == 0x31 -> {} // не поддерживается — обычный случай
                                raw.contains("NO DATA") || raw.contains("TIMEOUT") -> noReply++
                                else -> out.append("$didHex ERR ${r.message.replace("\n", " | ")}\n")
                            }
                        }
                        done++
                        if (done % 16 == 0) {
                            val sec = (System.currentTimeMillis() - started) / 1000
                            setStatus("Скан: $done/$total, найдено $hits, ${sec} с")
                        }
                    }
                }
            } catch (ex: Exception) {
                lost = ex
            }
            val sec = (System.currentTimeMillis() - started) / 1000
            out.append("# итого: проверено $done из $total, ответов $hits, без ответа $noReply, время $sec с")
            if (scanStop) out.append(", остановлен вручную")
            if (lost != null) out.append(", связь потеряна: ${lost.message}")
            out.append("\n")

            var file: File? = null
            try {
                file = File(ScanFileProvider.dir(this), "byd_scan_$stamp.txt")
                file.writeText(out.toString())
                log("Скан сохранён: ${file.name} (найдено $hits)")
            } catch (ex: Exception) {
                log("Не удалось сохранить файл: ${ex.message}")
                file = null
            }
            if (lost != null) onConnectionLost(lost)
            finishScan(file)
        }
    }

    private fun finishScan(file: File?) {
        scanning = false
        ui.post {
            scanBtn.text = "Запустить скан"
            if (file != null) {
                lastScanFile = file
                AlertDialog.Builder(this)
                    .setTitle("Скан завершён")
                    .setMessage("Файл ${file.name} сохранён. Отправить его?")
                    .setPositiveButton("Отправить") { _, _ -> shareFile(file) }
                    .setNegativeButton("Позже", null)
                    .show()
            }
            if (elm != null) {
                setStatus("Подключено")
                startPolling()
            }
        }
    }

    // ---------------------------------------------------------------- Терминал

    private fun sendTerminal() {
        val cmd = termInput.text.toString().trim().uppercase().replace(" ", "")
        if (cmd.isEmpty()) return
        val e = elm
        if (e == null) { toast("Сначала подключитесь к адаптеру"); return }
        if (scanning) { toast("Дождитесь окончания скана"); return }
        termInput.setText("")
        worker.execute {
            try {
                val r = e.send(cmd, 4000)
                log("> $cmd\n$r")
            } catch (ex: Exception) {
                log("> $cmd : ошибка ${ex.message}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scanStop = true
        pollTask?.cancel(false)
        worker.execute { closeQuietly() }
        worker.shutdown()
    }
}
