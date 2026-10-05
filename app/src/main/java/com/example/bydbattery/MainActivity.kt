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
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
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
        private const val MAX_LOG_LINES = 300
        private const val CELL_NOMINAL_V = 3.2 // LFP
    }

    private lateinit var kit: UiKit
    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private var pollTask: ScheduledFuture<*>? = null
    private var vehicleTask: ScheduledFuture<*>? = null
    @Volatile private var vehicleOn = false
    private val vvalues = HashMap<String, Double?>()
    @Volatile private var motorId: String? = null

    // --- Состояние соединения (поток worker)
    @Volatile private var elm: Elm327? = null
    @Volatile private var selectedEcu: Ecu? = null
    @Volatile private var craSupported = true

    // --- Настройки
    @Volatile private var header = "7E7"
    @Volatile private var cellCount = 90
    @Volatile private var verbose = false
    @Volatile private var showRaw = false

    // --- Длинные операции
    @Volatile private var busy = false
    @Volatile private var cancel = false
    private var lastScanFile: File? = null

    // --- Данные
    private val values = HashMap<String, Double?>()
    @Volatile private var energyIn = 0.0
    @Volatile private var energyOut = 0.0
    private var lastPollMs = 0L
    private var ecus = mutableListOf<Ecu>()
    private lateinit var recorder: Recorder

    // --- Views
    private lateinit var connView: TextView
    private lateinit var taskView: TextView
    private lateinit var connectBtn: Button
    private lateinit var stopBtn: Button
    private val tabs = mutableListOf<Pair<TextView, View>>()
    private val tiles = HashMap<String, Tile>()
    private lateinit var socBig: TextView
    private lateinit var socSub: TextView
    private lateinit var socBar: ProgressBar
    private lateinit var moduleView: TextView
    private lateinit var cellView: TextView
    private lateinit var dtcSpinner: Spinner
    private lateinit var dtcList: LinearLayout
    private lateinit var ecuList: LinearLayout
    private lateinit var obdView: TextView
    private lateinit var termSpinner: Spinner
    private lateinit var termInput: EditText
    private lateinit var logView: TextView
    private lateinit var recStatus: TextView
    private lateinit var recBtn: Button
    private lateinit var recBadge: TextView
    private lateinit var filesList: LinearLayout
    private lateinit var motorView: TextView
    private lateinit var watchSpinner: Spinner
    private lateinit var watchBtn: Button
    private lateinit var watchList: LinearLayout
    @Volatile private var watching = false
    private val logLines = ArrayDeque<String>()

    private fun bms(): Ecu {
        val req = header.toIntOrNull(16) ?: 0x7E7
        return Ecu(req, req + 8, "BMS (батарея)")
    }

    /** Список блоков для выбора: BMS + найденные. */
    private fun targets(): List<Ecu> = listOf(bms()) + ecus.filter { it.req != bms().req }.map { EcuNames.apply(it) }

    // =================================================================== UI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        kit = UiKit(this)
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        header = prefs.getString("header", "7E7") ?: "7E7"
        cellCount = prefs.getInt("cells", 90)
        showRaw = prefs.getBoolean("raw", false)
        ecus = EcuStore.load(this)
        recorder = Recorder(ScanFileProvider.dir(this))

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Palette.BG
        window.navigationBarColor = Palette.TAB_BG
        setContentView(buildUi())
        selectTab(0)
        refreshTargets()
        renderEcus()
        renderEnergy()
        setConn(false, "Не подключено")
    }

    private fun savePrefs() {
        getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putString("header", header).putInt("cells", cellCount).putBoolean("raw", showRaw).apply()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Palette.BG) }

        // Шапка
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(kit.dp(16), kit.dp(12), kit.dp(12), kit.dp(8))
        }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(kit.text("BYD e2 · диагностика", 18f, Palette.TEXT, true))
        connView = kit.text("", 13f, Palette.SUB)
        taskView = kit.text("", 12f, Palette.WARN).apply { visibility = View.GONE }
        recBadge = kit.text("● Идёт запись", 12f, Palette.BAD, true).apply { visibility = View.GONE }
        titles.addView(connView); titles.addView(recBadge); titles.addView(taskView)
        head.addView(titles, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        stopBtn = kit.button("Стоп") { cancel = true; taskView.text = "Останавливаю…" }.apply { visibility = View.GONE }
        head.addView(stopBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = kit.dp(8) })
        connectBtn = kit.button("Подключить", primary = true) { onConnectClick() }
        head.addView(connectBtn)
        root.addView(head)

        // Содержимое вкладок
        val content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        val pages = listOf(
            "Батарея" to buildBatteryTab(),
            "Машина" to buildVehicleTab(),
            "Модули" to buildModulesTab(),
            "Ошибки" to buildDtcTab(),
            "Блоки" to buildEcuTab(),
            "Сервис" to buildServiceTab(),
        )
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Palette.TAB_BG) }
        pages.forEachIndexed { i, (name, page) ->
            content.addView(page, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            val label = kit.tabLabel(name).apply { setOnClickListener { selectTab(i) } }
            bar.addView(label, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            tabs += label to page
        }
        root.addView(bar)
        return root
    }

    private fun selectTab(index: Int) {
        tabs.forEachIndexed { i, (label, page) ->
            val on = i == index
            page.visibility = if (on) View.VISIBLE else View.GONE
            label.setTextColor(if (on) Palette.ACCENT else Palette.SUB)
            label.setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        if (index == 5) renderFiles()
        vehicleOn = index == 1
        if (index == 1) refreshWatchTargets()
    }

    private fun page(): Pair<ScrollView, LinearLayout> {
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(kit.dp(12), kit.dp(4), kit.dp(12), kit.dp(12))
        }
        val sv = ScrollView(this).apply { addView(inner) }
        return sv to inner
    }

    private fun addTiles(card: LinearLayout, items: List<Pair<String, String>>) {
        for (pair in items.chunked(2)) {
            val views = pair.map { (key, title) ->
                val t = kit.tile(title)
                t.raw.visibility = if (showRaw) View.VISIBLE else View.GONE
                tiles[key] = t
                t.root
            }
            card.addView(kit.row(*views.toTypedArray()))
        }
    }

    // ---------- Вкладка «Батарея»
    private fun buildBatteryTab(): View {
        val (sv, p) = page()

        val socCard = kit.card(null)
        socCard.addView(kit.text("Заряд", 13f, Palette.SUB))
        socBig = kit.text("—", 44f, Palette.TEXT, true)
        socSub = kit.text("", 12f, Palette.SUB)
        socBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(Palette.ACCENT)
        }
        socCard.addView(socBig); socCard.addView(socSub)
        socCard.addView(socBar, LinearLayout.LayoutParams(MATCH_PARENT, kit.dp(10)).apply { topMargin = kit.dp(8) })
        p.addView(socCard)

        val rec = kit.card("Запись данных")
        recStatus = kit.note("Значения сохраняются в CSV-файл (открывается в Excel и Google Таблицах).")
        rec.addView(recStatus)
        recBtn = kit.button("Начать запись", primary = true) { onRecordClick() }
        rec.addView(recBtn)
        p.addView(rec)

        val now = kit.card("Сейчас")
        addTiles(now, listOf(
            "volt" to "Напряжение", "amp" to "Ток (− заряд)",
            "power" to "Мощность", "mode" to "Режим",
            "temp" to "Температура", "crate" to "C-rate",
        ))
        p.addView(now)

        val cells = kit.card("Ячейки")
        addTiles(cells, listOf("cell_min" to "Минимальная", "cell_max" to "Максимальная", "cell_dv" to "Разброс ΔV"))
        p.addView(cells)

        val temps = kit.card("Температуры")
        addTiles(temps, listOf("t_min" to "Минимальная", "t_max" to "Максимальная", "t_dt" to "Разброс ΔT"))
        p.addView(temps)

        val cap = kit.card("Ёмкость и ресурс")
        cap.addView(kit.note("Значения с пометкой «вероятно» расшифрованы по косвенным признакам."))
        addTiles(cap, listOf(
            "cap_act" to "Фактическая ёмкость (вероятно)", "cap" to "Номинальная (вероятно)",
            "soh" to "SOH (фактич. / номинал)", "p0029" to "Параметр 0029 (SOH?)",
            "ah_out" to "Отдано всего (вероятно)", "ah_in" to "Получено всего (вероятно)",
            "cycles" to "Эквивалент циклов", "e_life" to "Получено, кВт·ч ≈",
        ))
        p.addView(cap)

        val energy = kit.card("Энергия")
        addTiles(energy, listOf(
            "e_full" to "Запас при 100 %", "e_left" to "Осталось",
            "e_in" to "Получено с сброса", "e_out" to "Отдано с сброса",
        ))
        energy.addView(kit.button("Сбросить счётчики энергии") { energyIn = 0.0; energyOut = 0.0; renderEnergy() })
        p.addView(energy)
        return sv
    }

    // ---------- Вкладка «Машина»
    private fun buildVehicleTab(): View {
        val (sv, p) = page()

        val intro = kit.card(null)
        intro.addView(kit.note("Данные других блоков. Расшифрованы по одному снимку на стоящей машине, поэтому помечены " +
            "«вероятно». Проверьте: пробег — с приборкой, 12 В — с показанием адаптера. Обновляются раз в 4 секунды, " +
            "пока открыта эта вкладка или идёт запись."))
        p.addView(intro)

        val drive = kit.card("Движение")
        addTiles(drive, listOf("odo" to "Пробег (7E0 001B)", "speed" to "Скорость (OBD)", "v12_adapter" to "12 В по адаптеру"))
        p.addView(drive)

        val inv = kit.card("Инвертор / мотор (7E3, вероятно)")
        addTiles(inv, listOf("mcu_v" to "Напряжение HV", "mcu_t" to "Температуры 000E–0010", "mcu_ph" to "Температуры 1FF2"))
        motorView = kit.text("", 11f, Palette.SUB)
        inv.addView(motorView)
        p.addView(inv)

        val chg = kit.card("Зарядка / DC-DC (7E4, 793, вероятно)")
        addTiles(chg, listOf(
            "obc_v" to "Напряжение HV (7E4)", "obc_t" to "Температуры (7E4)",
            "dcdc_v" to "Напряжение HV (793)", "v12a" to "12 В? (793 0004)",
            "v12b" to "12 В? (793 0005)",
        ))
        p.addView(chg)

        val w = kit.card("Наблюдение за неизвестными параметрами")
        w.addView(kit.note("Опрашивает все ответившие при скане параметры выбранного блока и подсвечивает изменившиеся. " +
            "Изменения пишутся в файл — включите во время поездки или зарядки и пришлите его. " +
            "Опрос батареи на это время приостанавливается."))
        watchSpinner = Spinner(this)
        w.addView(watchSpinner)
        w.addView(kit.spacer(8))
        watchBtn = kit.button("Начать наблюдение", primary = true) { onWatchClick() }
        w.addView(watchBtn)
        watchList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, kit.dp(8), 0, 0) }
        w.addView(HorizontalScrollView(this).apply { addView(watchList) })
        p.addView(w)
        return sv
    }

    private fun watchTargets(): List<Ecu> = targets().filter { WatchStore.get(this, it.req).isNotEmpty() }

    private fun refreshWatchTargets() {
        val names = watchTargets().map { "${it.label} — ${WatchStore.get(this, it.req).size} пар." }
        val pos = watchSpinner.selectedItemPosition
        watchSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        if (pos in names.indices) watchSpinner.setSelection(pos)
    }

    // ---------- Опрос других блоков

    private fun pollVehicle() {
        val e = elm ?: return
        if (busy || !(vehicleOn || recorder.active)) return
        try {
            val atrv = e.send("ATRV")
            val v12 = Regex("([0-9]+[.,][0-9]+)").find(atrv)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
            vvalues["v12_adapter"] = v12
            setValue("v12_adapter", if (v12 != null) "%.1f В".format(v12) else "—")

            for ((req, params) in VPARAMS.groupBy { it.ecu }) {
                if (busy) return
                select(e, Ecu(req, req + 8, ""))
                for (p in params) {
                    val data = p.requests.map { rq ->
                        val raw = e.send(rq, 1500)
                        if (verbose) log("[%03X] $rq → ${raw.replace("\n", " | ")}".format(req))
                        val r = if (rq.startsWith("22")) Uds.parse(raw, rq.substring(2)) else Uds.parseService(raw, 0x01)
                        (r as? UdsResult.Ok)?.data
                    }
                    val v = try { p.decode(data) } catch (_: Exception) { null }
                    vvalues[p.key] = v?.num
                    setValue(p.key, v?.text ?: "—")
                    setRaw(p.key, data.joinToString(" | ") { d -> d?.joinToString(" ") { "%02X".format(it) } ?: "нет" })
                }
            }
            if (motorId == null) {
                select(e, Ecu(0x7E3, 0x7EB, ""))
                val d = readDid(e, 0xF1A0)
                motorId = if (d != null) asciiOrHex(d) else ""
                val text = motorId!!
                if (text.isNotEmpty()) ui.post { motorView.text = "Идентификатор мотора (F1A0): $text" }
            }
        } catch (ex: Exception) {
            onConnectionLost(ex)
        }
    }

    // ---------- Наблюдение

    private fun onWatchClick() {
        if (watching) { cancel = true; return }
        val list = watchTargets()
        val ecu = list.getOrNull(watchSpinner.selectedItemPosition) ?: run { toast("Нет блоков для наблюдения"); return }
        val dids = WatchStore.get(this, ecu.req)
        watchList.removeAllViews()
        val rows = dids.associateWith { did ->
            kit.mono(11f).apply { text = "%04X  …".format(did); setPadding(0, kit.dp(2), 0, kit.dp(2)) }.also { watchList.addView(it) }
        }
        runTask("Наблюдение ${ecu.short}") { e ->
            watching = true
            ui.post { watchBtn.text = "Остановить наблюдение" }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(ScanFileProvider.dir(this), "byd_watch_${ecu.short}_$stamp.csv")
            val tf = SimpleDateFormat("HH:mm:ss", Locale.US)
            val last = HashMap<Int, String>()
            val changes = HashMap<Int, Int>()
            var cycles = 0
            try {
                file.bufferedWriter(Charsets.UTF_8).use { w ->
                    w.write("\uFEFFВремя;Блок;DID;Байты;LE16;Байт0\n")
                    while (!cancel) {
                        select(e, ecu)
                        val now = System.currentTimeMillis()
                        for (did in dids) {
                            if (cancel) break
                            val d = readDid(e, did)
                            val hex = d?.joinToString(" ") { "%02X".format(it) } ?: "нет ответа"
                            val prev = last[did]
                            val changed = prev != null && prev != hex
                            if (prev == null || changed) {
                                if (changed) changes[did] = (changes[did] ?: 0) + 1
                                w.write("%s;%03X;%04X;%s;%s;%s\n".format(tf.format(Date(now)), ecu.req, did, hex,
                                    d?.let { le16(it, 0)?.toString() } ?: "", d?.getOrNull(0)?.toString() ?: ""))
                            }
                            last[did] = hex
                            val dec = d?.let { a ->
                                when {
                                    a.size == 1 -> "= ${a[0]}"
                                    a.size >= 2 -> "LE16 ${le16(a, 0)}"
                                    else -> ""
                                }
                            } ?: ""
                            val n = changes[did] ?: 0
                            val line = "%04X  %-24s %-12s %s".format(did, hex.take(24), dec, if (n > 0) "изм. $n" else "")
                            val color = if (changed) Palette.ACCENT else if (n > 0) Palette.WARN else Palette.TEXT
                            ui.post { rows[did]?.apply { text = line; setTextColor(color) } }
                        }
                        w.flush()
                        cycles++
                        setTask("Наблюдение ${ecu.short}: цикл $cycles, меняются ${changes.size} из ${dids.size}")
                    }
                }
            } finally {
                watching = false
                ui.post { watchBtn.text = "Начать наблюдение" }
                log("Наблюдение ${ecu.short} остановлено: циклов $cycles, менялись ${changes.size} параметров")
                if (cycles > 0) offerShare("Наблюдение остановлено", file)
            }
        }
    }

    // ---------- Вкладка «Модули»
    private fun buildModulesTab(): View {
        val (sv, p) = page()
        val c = kit.card("Модули батареи")
        c.addView(kit.note("Мин/макс ячейка и температура каждого из 7 модулей (016C–01A3). Номера ячеек — внутри модуля."))
        c.addView(kit.button("Прочитать модули и ячейки", primary = true) { readModules() })
        moduleView = kit.mono().apply { setPadding(0, kit.dp(10), 0, 0) }
        c.addView(HorizontalScrollView(this).apply { addView(moduleView) })
        p.addView(c)

        val c2 = kit.card("Параметр ячеек 0040–0099")
        c2.addView(kit.note("По одному значению на каждую из 90 ячеек. Смысл пока неизвестен, при зарядке не меняется. " +
            "Полезно сохранять и сравнивать раз в несколько месяцев."))
        cellView = kit.mono()
        c2.addView(HorizontalScrollView(this).apply { addView(cellView) })
        p.addView(c2)
        return sv
    }

    // ---------- Вкладка «Ошибки»
    private fun buildDtcTab(): View {
        val (sv, p) = page()
        val c = kit.card("Коды неисправностей")
        c.addView(kit.note("Блок:"))
        dtcSpinner = Spinner(this)
        c.addView(dtcSpinner)
        c.addView(kit.spacer(8))
        c.addView(kit.row(
            kit.button("Прочитать", primary = true) { readDtcSelected() },
            kit.button("Сбросить") { confirmClearDtc() }
        ))
        c.addView(kit.button("Проверить все блоки") { readDtcAll() })
        c.addView(kit.note("\nСбрасывайте ошибки только после того, как записали их и поняли причину. " +
            "Если неисправность настоящая, ошибка появится снова."))
        p.addView(c)
        dtcList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        p.addView(dtcList)
        return sv
    }

    // ---------- Вкладка «Блоки»
    private fun buildEcuTab(): View {
        val (sv, p) = page()
        val c = kit.card("Блоки автомобиля")
        c.addView(kit.note("Поиск перебирает адреса 700–7FF безопасным запросом «на связи?» и читает " +
            "идентификаторы ответивших блоков. Машина в режиме READY, около 2–3 минут."))
        c.addView(kit.button("Найти блоки", primary = true) { confirmDiscover() })
        c.addView(kit.spacer(8))
        c.addView(kit.button("Скан всех блоков") { askScanAll() })
        p.addView(c)
        ecuList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        p.addView(ecuList)

        val o = kit.card("Стандартный OBD-II")
        o.addView(kit.note("Проверяет, какие стандартные параметры (скорость, 12 В, одометр и т.п.) отдаёт машина."))
        o.addView(kit.button("Проверить OBD-II") { obdCheck() })
        obdView = kit.mono().apply { setPadding(0, kit.dp(10), 0, 0) }
        o.addView(obdView)
        p.addView(o)
        return sv
    }

    // ---------- Вкладка «Сервис»
    private fun buildServiceTab(): View {
        val (sv, p) = page()

        val s = kit.card("Настройки")
        val headerInput = EditText(this).apply {
            setText(header)
            setTextColor(Palette.TEXT)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        val cellsInput = EditText(this).apply {
            setText(cellCount.toString())
            setTextColor(Palette.TEXT)
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val l1 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        l1.addView(kit.text("Адрес BMS", 12f, Palette.SUB)); l1.addView(headerInput)
        val l2 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        l2.addView(kit.text("Число ячеек", 12f, Palette.SUB)); l2.addView(cellsInput)
        s.addView(kit.row(l1, l2))
        watch(headerInput) {
            header = it.trim().uppercase().ifEmpty { "7E7" }
            savePrefs(); refreshTargets()
        }
        watch(cellsInput) {
            cellCount = it.trim().toIntOrNull()?.takeIf { n -> n in 1..400 } ?: 90
            savePrefs()
        }
        s.addView(CheckBox(this).apply {
            text = "Показывать сырые ответы под значениями"
            setTextColor(Palette.TEXT)
            isChecked = showRaw
            setOnCheckedChangeListener { _, checked ->
                showRaw = checked; savePrefs()
                tiles.values.forEach { t -> t.raw.visibility = if (checked) View.VISIBLE else View.GONE }
            }
        })
        s.addView(CheckBox(this).apply {
            text = "Подробный журнал"
            setTextColor(Palette.TEXT)
            setOnCheckedChangeListener { _, checked -> verbose = checked }
        })
        p.addView(s)

        val sc = kit.card("Сканер BMS")
        sc.addView(kit.note("Перебирает идентификаторы BMS (только чтение) и сохраняет ответы в файл. " +
            "Сканер других блоков — на вкладке «Блоки»."))
        sc.addView(kit.row(
            kit.button("Запустить скан", primary = true) { askScan(bms()) },
            kit.button("Отправить файл") { lastScanFile?.let { shareFile(it) } ?: toast("Скан ещё не выполнялся") }
        ))
        p.addView(sc)

        val t = kit.card("Терминал")
        t.addView(kit.note("Команды ELM327 (AT…) или запросы UDS (например 220005) выбранному блоку."))
        termSpinner = Spinner(this)
        t.addView(termSpinner)
        termInput = EditText(this).apply {
            hint = "220005"
            setTextColor(Palette.TEXT)
            setHintTextColor(Palette.SUB)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        val sendBtn = kit.button("Отправить", primary = true) { sendTerminal() }
        val tr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        tr.addView(termInput, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        tr.addView(sendBtn)
        t.addView(tr)
        p.addView(t)

        val fc = kit.card("Сохранённые файлы")
        fc.addView(kit.note("Записи данных (CSV), сканы, отчёты об ошибках, блоках и модулях."))
        fc.addView(kit.button("Обновить список") { renderFiles() })
        filesList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, kit.dp(8), 0, 0) }
        fc.addView(filesList)
        p.addView(fc)

        val lg = kit.card("Журнал")
        lg.addView(kit.row(
            kit.button("Поделиться") { shareLog() },
            kit.button("Очистить") { logLines.clear(); logView.text = "" }
        ))
        logView = kit.mono(10f)
        lg.addView(logView)
        p.addView(lg)
        return sv
    }

    private fun watch(edit: EditText, onChange: (String) -> Unit) {
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { onChange(s?.toString() ?: "") }
        })
    }

    private fun refreshTargets() {
        val names = targets().map { it.label }
        for (sp in listOf(dtcSpinner, termSpinner)) {
            val pos = sp.selectedItemPosition
            sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
            if (pos in names.indices) sp.setSelection(pos)
        }
    }

    // ---------- Общие помощники UI

    private fun setConn(connected: Boolean, text: String) {
        ui.post {
            connView.text = "● $text"
            connView.setTextColor(if (connected) Palette.ACCENT else Palette.SUB)
        }
    }

    private fun setTask(text: String?) {
        ui.post {
            if (text == null) {
                taskView.visibility = View.GONE
                stopBtn.visibility = View.GONE
            } else {
                taskView.text = text
                taskView.visibility = View.VISIBLE
                stopBtn.visibility = View.VISIBLE
            }
        }
    }

    private fun setValue(key: String, text: String, color: Int = Palette.TEXT) {
        ui.post { tiles[key]?.value?.apply { this.text = text; setTextColor(color) } }
    }

    private fun setRaw(key: String, text: String) {
        ui.post { tiles[key]?.raw?.text = text }
    }

    private fun log(s: String) {
        val line = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + "  " + s
        ui.post {
            logLines.addLast(line)
            while (logLines.size > MAX_LOG_LINES) logLines.removeFirst()
            logView.text = logLines.joinToString("\n")
        }
    }

    private fun toast(s: String) { ui.post { Toast.makeText(this, s, Toast.LENGTH_LONG).show() } }

    private fun shareLog() {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, logLines.joinToString("\n"))
        }, "Отправить журнал"))
    }

    private fun shareFile(file: File) {
        val uri = ScanFileProvider.uriFor(file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (file.name.endsWith(".csv")) "text/csv" else "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Отправить ${file.name}"))
    }

    private fun saveText(prefix: String, text: String): File? = try {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        File(ScanFileProvider.dir(this), "${prefix}_$stamp.txt").apply { writeText(text) }
    } catch (ex: Exception) {
        log("Не удалось сохранить файл: ${ex.message}")
        null
    }

    private fun offerShare(title: String, file: File) {
        ui.post {
            lastScanFile = file
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage("Файл ${file.name} сохранён. Отправить его?")
                .setPositiveButton("Отправить") { _, _ -> shareFile(file) }
                .setNegativeButton("Позже", null)
                .show()
        }
    }

    // =================================================================== Bluetooth

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
        val name = device.name ?: device.address
        connectBtn.isEnabled = false
        setConn(false, "Подключение к $name…")
        worker.execute {
            try {
                val e = Elm327(openSocket(device))
                elm = e
                selectedEcu = null
                craSupported = true
                log("Bluetooth подключён, инициализация ELM327")
                val init = listOf("ATZ", "ATE0", "ATL0", "ATH0", "ATSP6", "ATAT1", "ATST96", "ATFCSD300000", "ATFCSM1")
                for (cmd in init) {
                    val r = e.send(cmd, if (cmd == "ATZ") 5000 else 2000)
                    log("$cmd → ${r.replace("\n", " | ")}")
                }
                select(e, bms())
                log("ATDPN → " + e.send("ATDPN"))
                log("Напряжение 12 В: " + e.send("ATRV"))
                setConn(true, "Подключено: $name")
                ui.post {
                    connectBtn.text = "Отключить"
                    connectBtn.isEnabled = true
                }
                startPolling()
            } catch (ex: Exception) {
                log("Ошибка: ${ex.message}")
                setConn(false, "Ошибка подключения")
                closeQuietly()
                ui.post { connectBtn.isEnabled = true; connectBtn.text = "Подключить" }
            }
        }
    }

    private fun disconnect() {
        if (recorder.active) stopRecording()
        cancel = true
        stopPolling()
        worker.execute {
            closeQuietly()
            log("Отключено")
            setConn(false, "Не подключено")
            ui.post { connectBtn.text = "Подключить" }
        }
    }

    private fun closeQuietly() {
        elm?.close()
        elm = null
        selectedEcu = null
    }

    private fun onConnectionLost(ex: Exception) {
        recorder.flush()
        log("Связь потеряна: ${ex.message}" + if (recorder.active) " (запись продолжится после переподключения)" else "")
        setConn(false, "Связь потеряна")
        stopPolling()
        closeQuietly()
        ui.post { connectBtn.text = "Подключить"; connectBtn.isEnabled = true }
    }

    // =================================================================== Работа с адаптером

    /** Направляет запросы указанному блоку (адрес запроса, приём ответов, flow control). */
    private fun select(e: Elm327, ecu: Ecu) {
        val cur = selectedEcu
        if (cur != null && cur.req == ecu.req && cur.resp == ecu.resp) return
        val h = "%03X".format(ecu.req)
        e.send("ATSH$h")
        e.send("ATFCSH$h")
        if (craSupported) {
            val r = e.send("ATCRA%03X".format(ecu.resp))
            if (r.contains("?")) {
                craSupported = false
                e.send("ATAR")
                log("Адаптер не поддерживает ATCRA — используется автоприём (блоки вне 7E0–7E7 могут не отвечать)")
            }
        }
        selectedEcu = ecu
    }

    /** Возвращает адаптер к базовым настройкам после нестандартных операций. */
    private fun restoreBase(e: Elm327) {
        for (cmd in listOf("ATH0", "ATCRA", "ATAR", "ATST96", "ATFCSD300000", "ATFCSM1")) e.send(cmd)
        selectedEcu = null
    }

    /** Выполняет длинную операцию на рабочем потоке; опрос на это время приостанавливается. */
    private fun runTask(name: String, block: (Elm327) -> Unit) {
        val e = elm
        if (e == null) { toast("Сначала подключитесь к адаптеру"); return }
        if (busy) { toast("Подождите, выполняется другая операция"); return }
        busy = true
        cancel = false
        setTask(name)
        worker.execute {
            try {
                block(e)
            } catch (ex: IOException) {
                onConnectionLost(ex)
            } catch (ex: Exception) {
                log("Ошибка: ${ex.message}")
            } finally {
                busy = false
                setTask(null)
            }
        }
    }

    // =================================================================== Опрос BMS

    private fun startPolling() {
        if (pollTask != null) return
        lastPollMs = 0L
        pollTask = worker.scheduleWithFixedDelay({ pollOnce() }, 0, 1000, TimeUnit.MILLISECONDS)
        vehicleTask = worker.scheduleWithFixedDelay({ pollVehicle() }, 2, 4, TimeUnit.SECONDS)
    }

    private fun stopPolling() {
        pollTask?.cancel(false)
        pollTask = null
        vehicleTask?.cancel(false)
        vehicleTask = null
    }

    private fun pollOnce() {
        val e = elm ?: return
        if (busy) { lastPollMs = 0L; return }
        try {
            select(e, bms())
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
                        setRaw(p.key, "22 ${p.did}: ${r.rawHex}")
                    }
                    is UdsResult.Error -> {
                        values[p.key] = null
                        setValue(p.key, "—")
                        setRaw(p.key, "22 ${p.did}: ${r.message}")
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
        val socD = values["soc_d"]
        val soc = values["soc"] ?: socD
        val capAct = values["cap_act"]
        val capNom = values["cap"]
        val cap = capAct ?: capNom
        val u = values["volt"]
        val i = values["amp"]

        ui.post {
            socBig.text = if (socD != null) "%.0f %%".format(socD) else if (soc != null) "%.1f %%".format(soc) else "—"
            socSub.text = if (values["soc"] != null) "BMS: %.2f %%".format(values["soc"]) else ""
            socBar.progress = ((soc ?: 0.0) * 10).toInt().coerceIn(0, 1000)
        }

        val power = if (u != null && i != null) u * i / 1000.0 else null
        setValue("power", fmt(power, "%.2f кВт"))
        when {
            i == null -> setValue("mode", "—")
            i > 0.5 -> setValue("mode", "разряд", Palette.WARN)
            i < -0.5 -> setValue("mode", "заряд", Palette.ACCENT)
            else -> setValue("mode", "покой")
        }
        setValue("crate", if (i != null && cap != null && cap > 0) "%.3f C".format(i / cap) else "—")

        val cminV = values["cmin_v"]; val cmaxV = values["cmax_v"]
        setValue("cell_min", if (cminV != null) "%.3f В №%d".format(cminV, (values["cmin_n"] ?: 0.0).toInt()) else "—")
        setValue("cell_max", if (cmaxV != null) "%.3f В №%d".format(cmaxV, (values["cmax_n"] ?: 0.0).toInt()) else "—")
        if (cminV != null && cmaxV != null) {
            val dv = (cmaxV - cminV) * 1000
            setValue("cell_dv", "%.0f мВ".format(dv), when { dv <= 15 -> Palette.ACCENT; dv <= 40 -> Palette.WARN; else -> Palette.BAD })
        } else setValue("cell_dv", "—")

        val tmin = values["tmin"]; val tmax = values["tmax"]
        setValue("t_min", if (tmin != null) "%.0f °C д.%d".format(tmin, (values["tmin_n"] ?: 0.0).toInt()) else "—")
        setValue("t_max", if (tmax != null) "%.0f °C д.%d".format(tmax, (values["tmax_n"] ?: 0.0).toInt()) else "—")
        if (tmin != null && tmax != null) {
            val dt = tmax - tmin
            setValue("t_dt", "%.0f °C".format(dt), when { dt <= 3 -> Palette.ACCENT; dt <= 6 -> Palette.WARN; else -> Palette.BAD })
        } else setValue("t_dt", "—")

        val soh = if (capAct != null && capNom != null && capNom > 0) capAct / capNom * 100 else null
        setValue("soh", fmt(soh, "%.1f %%"))
        val ahIn = values["ah_in"]
        setValue("cycles", if (ahIn != null && capNom != null && capNom > 0) "%.0f".format(ahIn / capNom) else "—")
        setValue("e_life", if (ahIn != null) "%.0f кВт·ч".format(ahIn * cellCount * CELL_NOMINAL_V / 1000.0) else "—")

        val eFull = cap?.let { it * cellCount * CELL_NOMINAL_V / 1000.0 }
        setValue("e_full", fmt(eFull, "%.1f кВт·ч"))
        setValue("e_left", if (eFull != null && soc != null) "%.1f кВт·ч".format(eFull * soc / 100.0) else "—")

        if (power != null && lastPollMs > 0) {
            val dtMs = now - lastPollMs
            if (dtMs in 1..10_000) {
                val kwh = power * dtMs / 3_600_000.0
                if (kwh >= 0) energyOut += kwh else energyIn += -kwh
            }
        }
        lastPollMs = now
        renderEnergy()

        if (recorder.active) {
            val row = HashMap<String, Double?>(values)
            row.putAll(vvalues)
            row["power_kw"] = power
            row["dv_mv"] = if (cminV != null && cmaxV != null) (cmaxV - cminV) * 1000 else null
            row["dt_c"] = if (tmin != null && tmax != null) tmax - tmin else null
            row["soh"] = soh
            row["e_left_kwh"] = if (eFull != null && soc != null) eFull * soc / 100.0 else null
            row["e_in_kwh"] = energyIn
            row["e_out_kwh"] = energyOut
            try {
                if (recorder.maybeWrite(now, row)) renderRecStatus()
            } catch (ex: Exception) {
                log("Ошибка записи: ${ex.message}")
                recorder.stop()
                renderRecStatus()
            }
        }
    }

    // =================================================================== Запись данных

    private fun onRecordClick() {
        if (recorder.active) { stopRecording(); return }
        val options = arrayOf("Каждую секунду", "Каждые 5 секунд", "Каждые 30 секунд", "Каждую минуту")
        val intervals = longArrayOf(1000, 5000, 30_000, 60_000)
        AlertDialog.Builder(this)
            .setTitle("Как часто записывать?")
            .setItems(options) { _, which ->
                try {
                    val f = recorder.start(intervals[which])
                    log("Запись начата: ${f.name} (${options[which].lowercase()})")
                    if (elm == null) toast("Запись начнётся после подключения к адаптеру")
                } catch (ex: Exception) {
                    toast("Не удалось создать файл: ${ex.message}")
                }
                renderRecStatus()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun stopRecording() {
        val f = recorder.stop()
        renderRecStatus()
        if (f != null) {
            log("Запись остановлена: ${f.name}, строк ${recorder.rows}")
            offerShare("Запись остановлена", f)
        }
    }

    private fun renderRecStatus() {
        ui.post {
            val f = recorder.file
            if (recorder.active && f != null) {
                val sec = (System.currentTimeMillis() - recorder.startMs) / 1000
                recStatus.text = "Идёт запись: %02d:%02d:%02d, строк %d\n%s, %.1f КБ".format(
                    sec / 3600, sec / 60 % 60, sec % 60, recorder.rows, f.name, f.length() / 1024.0)
                recStatus.setTextColor(Palette.TEXT)
                recBtn.text = "Остановить запись"
                recBadge.visibility = View.VISIBLE
            } else {
                recStatus.text = "Значения сохраняются в CSV-файл (открывается в Excel и Google Таблицах)."
                recStatus.setTextColor(Palette.SUB)
                recBtn.text = "Начать запись"
                recBadge.visibility = View.GONE
            }
        }
    }

    // =================================================================== Файлы

    private fun renderFiles() {
        filesList.removeAllViews()
        val files = ScanFileProvider.dir(this).listFiles { f -> f.isFile && f.name.startsWith("byd_") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (files.isEmpty()) {
            filesList.addView(kit.note("Файлов пока нет."))
            return
        }
        val df = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US)
        for (f in files.take(60)) {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = kit.rounded(Palette.TILE, 10)
                setPadding(kit.dp(10), kit.dp(8), kit.dp(10), kit.dp(8))
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = kit.dp(8) }
            }
            box.addView(kit.text(f.name, 13f, Palette.TEXT, true))
            box.addView(kit.text("%s · %.1f КБ".format(df.format(Date(f.lastModified())), f.length() / 1024.0), 11f, Palette.SUB))
            val recording = recorder.active && recorder.file == f
            box.addView(kit.row(
                kit.button("Отправить") {
                    if (recording) recorder.flush()
                    shareFile(f)
                },
                kit.button(if (recording) "Идёт запись" else "Удалить") {
                    if (recording) return@button
                    AlertDialog.Builder(this)
                        .setTitle("Удалить файл?")
                        .setMessage(f.name)
                        .setPositiveButton("Удалить") { _, _ -> f.delete(); renderFiles() }
                        .setNegativeButton("Отмена", null)
                        .show()
                }
            ).apply { setPadding(0, kit.dp(6), 0, 0) })
            filesList.addView(box)
        }
    }

    private fun renderEnergy() {
        setValue("e_in", "%.3f кВт·ч".format(energyIn))
        setValue("e_out", "%.3f кВт·ч".format(energyOut))
    }

    // =================================================================== Модули

    private fun readDid(e: Elm327, did: Int): IntArray? {
        val h = "%04X".format(did)
        return (Uds.parse(e.send("22$h"), h) as? UdsResult.Ok)?.data
    }

    private fun readModules() {
        moduleView.text = "Чтение…"
        runTask("Чтение модулей и ячеек") { e ->
            select(e, bms())
            val sb = StringBuilder()
            sb.append("Мод  мин.ячейка   макс.ячейка   ΔV      темп.\n")
            for (m in 0 until MODULE_COUNT) {
                if (cancel) break
                val base = MODULE_BASE + m * 8
                val v = (0 until 8).map { readDid(e, base + it) }
                val nMin = v[0]?.getOrNull(0); val vMin = v[1]?.let { le16(it, 0) }
                val nMax = v[2]?.getOrNull(0); val vMax = v[3]?.let { le16(it, 0) }
                val tMin = v[5]?.getOrNull(0)?.let { it - 40 }
                val tMax = v[7]?.getOrNull(0)?.let { it - 40 }
                fun cell(n: Int?, mv: Int?) = if (n == null || mv == null) "  ?          " else "#%-2d %.3f В".format(n + 1, mv / 1000.0)
                val dv = if (vMin != null && vMax != null) "%3d мВ".format(vMax - vMin) else "  ?   "
                val t = if (tMin != null && tMax != null) (if (tMin == tMax) "$tMin °C" else "$tMin…$tMax °C") else "?"
                sb.append("%-4d %s  %s  %s  %s\n".format(m + 1, cell(nMin, vMin), cell(nMax, vMax), dv, t))
            }
            val modText = sb.toString()
            ui.post { moduleView.text = modText }

            val cells = (0 until CELL_PARAM_COUNT).map { i -> if (cancel) null else readDid(e, CELL_PARAM_BASE + i)?.let { le16(it, 0) } }
            val known = cells.withIndex().filter { it.value != null }.map { it.index to it.value!! }
            val cb = StringBuilder()
            if (known.isNotEmpty()) {
                val mn = known.minBy { it.second }
                val mx = known.maxBy { it.second }
                val avg = known.sumOf { it.second } / known.size.toDouble()
                cb.append("мин %d (ячейка %d), макс %d (ячейка %d), среднее %.1f\n\n".format(
                    mn.second, mn.first + 1, mx.second, mx.first + 1, avg))
                for (row in cells.indices.chunked(10)) {
                    cb.append("%2d–%2d: ".format(row.first() + 1, row.last() + 1))
                    cb.append(row.joinToString(" ") { idx -> cells[idx]?.let { "%3d".format(it) } ?: "  ?" })
                    cb.append("\n")
                }
            } else cb.append("нет ответа")
            val cellText = cb.toString()
            ui.post { cellView.text = cellText }
            log("Модули и ячейки прочитаны\n$modText\n$cellText")
            val snap = PARAMS.joinToString(", ") { p -> "${p.key}=${values[p.key]?.let { "%.2f".format(it) } ?: "?"}" }
            saveText("byd_modules", "# Модули и ячейки, ${Date()}\n# $snap\n\n$modText\nПараметр ячеек 0040–0099:\n$cellText")
                ?.let { log("Сохранено: ${it.name}") }
        }
    }

    // =================================================================== Ошибки

    private fun selectedTarget(sp: Spinner): Ecu = targets().getOrElse(sp.selectedItemPosition) { bms() }

    private fun readDtcFor(e: Elm327, ecu: Ecu): Pair<List<Dtc>?, String?> {
        select(e, ecu)
        for (mask in listOf("FF", "0D", "08")) {
            val raw = e.send("1902$mask", 5000)
            if (verbose) log("1902$mask → ${raw.replace("\n", " | ")}")
            when (val r = Uds.parseService(raw, 0x19)) {
                is UdsResult.Ok -> return Dtcs.parse(r.data) to null
                is UdsResult.Error -> if (r.nrc == 0x31 || r.nrc == 0x12) continue else return null to r.message
            }
        }
        return null to "блок не поддерживает чтение ошибок"
    }

    private fun dtcCard(ecu: Ecu, list: List<Dtc>?, err: String?): View {
        val c = kit.card(ecu.label)
        when {
            err != null -> c.addView(kit.text(err, 13f, Palette.WARN))
            list.isNullOrEmpty() -> c.addView(kit.text("Ошибок нет", 14f, Palette.ACCENT, true))
            else -> for (d in list) {
                c.addView(kit.text(d.code, 16f, if (d.active || d.confirmed) Palette.BAD else Palette.WARN, true))
                c.addView(kit.text(d.statusText, 12f, Palette.SUB).apply { setPadding(0, 0, 0, kit.dp(6)) })
            }
        }
        return c
    }

    private fun dtcReport(ecu: Ecu, list: List<Dtc>?, err: String?) = buildString {
        append("${ecu.label}: ")
        when {
            err != null -> append(err)
            list.isNullOrEmpty() -> append("ошибок нет")
            else -> { append("\n"); list.forEach { append("  ${it.code}  ${it.statusText}\n") } }
        }
    }

    private fun readDtcSelected() {
        val ecu = selectedTarget(dtcSpinner)
        dtcList.removeAllViews()
        runTask("Чтение ошибок ${ecu.short}") { e ->
            val (list, err) = readDtcFor(e, ecu)
            log(dtcReport(ecu, list, err))
            ui.post { dtcList.addView(dtcCard(ecu, list, err)) }
        }
    }

    private fun readDtcAll() {
        val all = targets()
        dtcList.removeAllViews()
        runTask("Проверка всех блоков") { e ->
            val report = StringBuilder("# Коды ошибок, ${Date()}\n")
            for ((n, ecu) in all.withIndex()) {
                if (cancel) break
                setTask("Ошибки: блок ${n + 1} из ${all.size} (${ecu.short})")
                val (list, err) = readDtcFor(e, ecu)
                report.append(dtcReport(ecu, list, err)).append("\n")
                ui.post { dtcList.addView(dtcCard(ecu, list, err)) }
            }
            log(report.toString())
            saveText("byd_dtc", report.toString())?.let { offerShare("Проверка завершена", it) }
        }
    }

    private fun confirmClearDtc() {
        val ecu = selectedTarget(dtcSpinner)
        AlertDialog.Builder(this)
            .setTitle("Сбросить ошибки?")
            .setMessage("Будут удалены все коды неисправностей блока ${ecu.label}.\n\n" +
                "Сначала прочитайте и сохраните их. Сброс выполняйте на стоящей машине с включённым зажиганием.")
            .setPositiveButton("Сбросить") { _, _ -> clearDtc(ecu) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun clearDtc(ecu: Ecu) {
        dtcList.removeAllViews()
        runTask("Сброс ошибок ${ecu.short}") { e ->
            select(e, ecu)
            val raw = e.send("14FFFFFF", 8000)
            val result = when (val r = Uds.parseService(raw, 0x14)) {
                is UdsResult.Ok -> "Ошибки блока ${ecu.label} сброшены"
                is UdsResult.Error -> "Не удалось сбросить: ${r.message}"
            }
            log(result)
            toast(result)
            Thread.sleep(500)
            val (list, err) = readDtcFor(e, ecu)
            ui.post { dtcList.addView(dtcCard(ecu, list, err)) }
        }
    }

    // =================================================================== Поиск блоков

    private fun confirmDiscover() {
        AlertDialog.Builder(this)
            .setTitle("Найти блоки?")
            .setMessage("Приложение отправит на адреса 700–7FF безопасные запросы «на связи?» и прочитает " +
                "идентификаторы ответивших блоков. Ничего не изменяется. Займёт 2–3 минуты, машина в режиме READY.")
            .setPositiveButton("Начать") { _, _ -> discover() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun discover() {
        runTask("Поиск блоков") { e ->
            restoreBase(e)
            e.send("ATH1")
            e.send("ATST32")
            val r = e.send("ATCRA7XX")
            if (r.contains("?")) { e.send("ATCF700"); e.send("ATCM700") }

            val found = LinkedHashMap<Int, Int>()
            val respIds = HashSet<Int>()
            for (id in 0x700..0x7FF) {
                if (cancel) break
                if (id == 0x7DF || id in respIds) continue
                if (id % 8 == 0) setTask("Поиск блоков: %03X, найдено %d".format(id, found.size))
                e.send("ATSH%03X".format(id))
                for (probe in listOf("3E00", "1001")) {
                    val frames = CanFrames.assemble(e.send(probe, 1500))
                    val hits = frames.filter { (rid, d) -> rid != id && d.isNotEmpty() && (d[0] == 0x7E || d[0] == 0x50 || d[0] == 0x7F) }
                    if (hits.isNotEmpty()) {
                        val rid = hits.keys.first()
                        found[id] = rid
                        respIds += hits.keys
                        log("Блок: запрос %03X → ответ %03X".format(id, rid))
                        break
                    }
                }
            }
            restoreBase(e)

            val list = mutableListOf<Ecu>()
            for ((n, entry) in found.entries.withIndex()) {
                if (cancel) break
                val (req, resp) = entry
                setTask("Идентификация: %d из %d (%03X)".format(n + 1, found.size, req))
                val tmp = Ecu(req, resp, "")
                select(e, tmp)
                val info = StringBuilder()
                var sysName: String? = null
                for ((did, title) in IDENT_DIDS) {
                    val d = readDid(e, did.toInt(16)) ?: continue
                    val v = asciiOrHex(d)
                    if (did == "F197" && d.any { it in 0x41..0x7A }) sysName = v
                    info.append("$title: $v\n")
                }
                val name = if (req == bms().req) "BMS (батарея)" else EcuNames.of(req, sysName ?: "")
                list += Ecu(req, resp, name, info.toString().trim())
            }
            restoreBase(e)

            ui.post {
                if (list.isNotEmpty() || !cancel) {
                    ecus = list
                    EcuStore.save(this, ecus)
                    refreshTargets()
                    renderEcus()
                }
            }
            val report = buildString {
                append("# Найденные блоки, ${Date()}\n")
                for (ecu in list) append("\n${ecu.label}\n${decorateInfo(ecu.info)}\n")
            }
            log(report)
            saveText("byd_ecus", report)?.let { offerShare("Найдено блоков: ${list.size}", it) }
        }
    }

    private fun renderEcus() {
        ecuList.removeAllViews()
        if (ecus.isEmpty()) {
            ecuList.addView(kit.card(null).apply { addView(kit.note("Блоки ещё не искались.")) })
            return
        }
        for (raw in ecus) {
            val ecu = EcuNames.apply(raw)
            val c = kit.card(ecu.label)
            if (ecu.info.isNotEmpty()) c.addView(kit.text(decorateInfo(ecu.info), 11f, Palette.SUB).apply { setPadding(0, 0, 0, kit.dp(8)) })
            c.addView(kit.row(
                kit.button("Ошибки") {
                    val idx = targets().indexOfFirst { it.req == ecu.req }
                    if (idx >= 0) dtcSpinner.setSelection(idx)
                    selectTab(3)
                    readDtcSelected()
                },
                kit.button("Скан параметров") { askScan(ecu) }
            ))
            ecuList.addView(c)
        }
    }

    // =================================================================== OBD-II

    private fun obdCheck() {
        obdView.text = "Проверка…"
        runTask("Проверка OBD-II") { e ->
            restoreBase(e)
            e.send("ATH1")
            e.send("ATSH7DF")
            e.send("ATFCSM0")
            val supported = LinkedHashMap<Int, MutableSet<Int>>()
            for (base in listOf(0x00, 0x20, 0x40, 0x60, 0x80, 0xA0)) {
                if (cancel) break
                val frames = CanFrames.assemble(e.send("01%02X".format(base), 3000))
                var next = false
                for ((id, d) in frames) {
                    if (d.size < 6 || d[0] != 0x41 || d[1] != base) continue
                    val set = supported.getOrPut(id) { sortedSetOf<Int>() }
                    for (bit in 0 until 32) {
                        if (((d[2 + bit / 8] shr (7 - bit % 8)) and 1) == 1) set += base + bit + 1
                    }
                    if (base + 0x20 in set) next = true
                }
                if (!next) break
            }
            val sb = StringBuilder()
            if (supported.isEmpty()) {
                sb.append("Машина не отвечает на стандартные запросы OBD-II.\n")
            } else {
                for ((id, set) in supported) {
                    sb.append("Блок %03X поддерживает: %s\n".format(id, set.joinToString(" ") { "%02X".format(it) }))
                }
                sb.append("\n")
                val all = supported.values.flatten().toSet()
                for (pid in all.sorted()) {
                    if (cancel) break
                    if (pid % 0x20 == 0) continue // это маски поддерживаемых PID
                    val raw = e.send("01%02X".format(pid), 3000)
                    val frames = CanFrames.assemble(raw)
                    val def = ObdPids.KNOWN[pid]
                    var shown = false
                    for ((id, d) in frames) {
                        if (d.size < 2 || d[0] != 0x41 || d[1] != pid) continue
                        val data = d.copyOfRange(2, d.size)
                        val v = def?.second?.invoke(data)
                        val title = def?.first ?: "PID %02X".format(pid)
                        sb.append("%s: %s  [%03X]\n".format(title, v ?: data.joinToString(" ") { "%02X".format(it) }, id))
                        shown = true
                    }
                    if (!shown) sb.append("PID %02X: не разобран, ответ: %s\n".format(pid, raw.replace("\n", " | ")))
                }
                val vin = CanFrames.assemble(e.send("0902", 4000))
                for ((id, d) in vin) {
                    if (d.size > 3 && d[0] == 0x49 && d[1] == 0x02) sb.append("VIN: %s  [%03X]\n".format(asciiOrHex(d.copyOfRange(3, d.size)), id))
                }
            }
            restoreBase(e)
            val text = sb.toString()
            ui.post { obdView.text = text }
            log("OBD-II:\n$text")
        }
    }

    // =================================================================== Сканер параметров

    private fun askScan(ecu: Ecu) {
        val options = arrayOf(
            "Быстрый: 0000–00FF, 1F00–1FFF, F180–F1FF (~1 мин)",
            "Полный: 0000–0FFF, 1F00–1FFF, F180–F1FF (~7 мин)",
            "Диапазон производителя: F000–FFFF (~6 мин)"
        )
        AlertDialog.Builder(this)
            .setTitle("Скан: ${ecu.label}")
            .setItems(options) { _, which -> runScan(ecu, which) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun scanRanges(mode: Int) = when (mode) {
        0 -> listOf(0x0000..0x00FF, 0x1F00..0x1FFF, 0xF180..0xF1FF)
        1 -> listOf(0x0000..0x0FFF, 0x1F00..0x1FFF, 0xF180..0xF1FF)
        else -> listOf(0xF000..0xFFFF)
    }

    private fun snapshotBms() =
        PARAMS.joinToString(", ") { p -> "${p.key}=${values[p.key]?.let { "%.2f".format(it) } ?: "?"}" }

    private class ScanStats(var done: Int = 0, var hits: Int = 0, var noReply: Int = 0, var aborted: String? = null)

    /** Сканирует один блок и дописывает результаты в out. IOException пробрасывается наверх. */
    private fun scanInto(e: Elm327, ecu: Ecu, ranges: List<IntRange>, out: StringBuilder, progress: (ScanStats, Int) -> Unit): ScanStats {
        select(e, ecu)
        val total = ranges.sumOf { it.count() }
        val st = ScanStats()
        val found = mutableListOf<Pair<Int, Int>>()
        outer@ for (range in ranges) {
            for (did in range) {
                if (cancel) { st.aborted = "остановлен вручную"; break@outer }
                val h = "%04X".format(did)
                val raw = e.send("22$h", 2500)
                when (val r = Uds.parse(raw, h)) {
                    is UdsResult.Ok -> {
                        st.hits++
                        found += did to r.data.size
                        out.append("$h OK len=${r.data.size} : ${r.data.joinToString(" ") { "%02X".format(it) }}${Hints.of(r.data)}\n")
                    }
                    is UdsResult.Error -> when {
                        r.nrc == 0x31 -> {}
                        raw.contains("NO DATA") || raw.contains("TIMEOUT") -> st.noReply++
                        else -> out.append("$h ERR ${r.message.replace("\n", " | ")}\n")
                    }
                }
                st.done++
                if (st.done == 30 && st.noReply == 30) { st.aborted = "блок не отвечает на запросы 22"; break@outer }
                if (st.done % 16 == 0) progress(st, total)
            }
        }
        if (st.aborted == null) WatchStore.put(this, ecu.req, WatchStore.filter(found))
        out.append("# ${ecu.short}: проверено ${st.done} из $total, ответов ${st.hits}, без ответа ${st.noReply}")
        st.aborted?.let { out.append(", $it") }
        out.append("\n")
        return st
    }

    private fun runScan(ecu: Ecu, mode: Int) {
        val ranges = scanRanges(mode)
        val modeName = listOf("quick", "full", "vendor")[mode]
        val snapshot = snapshotBms()
        runTask("Скан ${ecu.short}") { e ->
            val out = StringBuilder()
            out.append("# BYD scan ${Date()}\n")
            out.append("# ecu=${ecu.label} mode=$modeName cells=$cellCount\n")
            out.append("# snapshot BMS: $snapshot\n")
            out.append("# формат: DID  OK len=N : байты данных после 62 XX XX\n")
            val started = System.currentTimeMillis()
            try {
                val st = scanInto(e, ecu, ranges, out) { st, total ->
                    val sec = (System.currentTimeMillis() - started) / 1000
                    setTask("Скан ${ecu.short}: ${st.done}/$total, найдено ${st.hits}, $sec с")
                }
                log("Скан ${ecu.short} завершён: найдено ${st.hits}${st.aborted?.let { " ($it)" } ?: ""}")
                saveText("byd_scan_${ecu.short}", out.toString())?.let { offerShare("Скан завершён", it) }
            } catch (ex: IOException) {
                out.append("# связь потеряна: ${ex.message}\n")
                saveText("byd_scan_${ecu.short}", out.toString())?.let { offerShare("Скан прерван", it) }
                throw ex
            }
        }
    }

    private fun askScanAll() {
        if (ecus.isEmpty()) { toast("Сначала найдите блоки"); return }
        val n = targets().size
        val options = arrayOf(
            "Быстрый: около ${n * 3 / 2} мин на $n блоков",
            "Полный: около ${n * 7} мин на $n блоков"
        )
        AlertDialog.Builder(this)
            .setTitle("Скан всех блоков")
            .setItems(options) { _, which -> runScanAll(which) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun runScanAll(mode: Int) {
        val list = targets()
        val ranges = scanRanges(mode)
        val snapshot = snapshotBms()
        runTask("Скан всех блоков") { e ->
            val out = StringBuilder()
            out.append("# BYD scan ALL ${Date()}\n")
            out.append("# mode=${listOf("quick", "full")[mode]} blocks=${list.size}\n")
            out.append("# snapshot BMS: $snapshot\n")
            out.append("# формат: DID  OK len=N : байты данных после 62 XX XX\n")
            val started = System.currentTimeMillis()
            var totalHits = 0
            try {
                for ((n, ecu) in list.withIndex()) {
                    if (cancel) break
                    out.append("\n## ${ecu.label}\n")
                    val st = scanInto(e, ecu, ranges, out) { st, total ->
                        val min = (System.currentTimeMillis() - started) / 60000
                        setTask("Блок ${n + 1} из ${list.size} (${ecu.short}): ${st.done}/$total, найдено ${st.hits} · $min мин")
                    }
                    totalHits += st.hits
                    log("Скан ${ecu.short}: найдено ${st.hits}${st.aborted?.let { " ($it)" } ?: ""}")
                }
                val min = (System.currentTimeMillis() - started) / 60000
                out.append("\n# итого: ответов $totalHits, время $min мин${if (cancel) ", остановлен вручную" else ""}\n")
                saveText("byd_scan_all", out.toString())?.let { offerShare("Скан всех блоков завершён", it) }
            } catch (ex: IOException) {
                out.append("\n# связь потеряна: ${ex.message}\n")
                saveText("byd_scan_all", out.toString())?.let { offerShare("Скан прерван", it) }
                throw ex
            }
        }
    }

    // =================================================================== Терминал

    private fun sendTerminal() {
        val cmd = termInput.text.toString().trim().uppercase().replace(" ", "")
        if (cmd.isEmpty()) return
        val ecu = selectedTarget(termSpinner)
        termInput.setText("")
        runTask("Терминал") { e ->
            select(e, ecu)
            val r = e.send(cmd, 5000)
            log("[${ecu.short}] > $cmd\n$r")
            if (cmd.startsWith("AT")) selectedEcu = null // состояние адаптера могло измениться
        }
    }

    override fun onPause() {
        super.onPause()
        recorder.flush()
    }

    override fun onDestroy() {
        super.onDestroy()
        recorder.stop()
        cancel = true
        pollTask?.cancel(false)
        worker.execute { closeQuietly() }
        worker.shutdown()
    }
}
