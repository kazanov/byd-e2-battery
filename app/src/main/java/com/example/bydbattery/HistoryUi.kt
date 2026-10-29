package com.example.bydbattery

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small, dependency-free line chart. Missing samples and interrupted polling leave visible gaps. */
class ChargeChart(context: Context, points: List<ChargePoint>, private val unit: String,
                  private val select: (ChargePoint) -> Double?) : View(context) {
    private val samples = points.toList()
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    init {
        minimumHeight = (180 * density).toInt()
        contentDescription = "График $unit по времени зарядки. Подробные значения доступны в экспорте JSON."
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val usable = samples.mapNotNull { p -> select(p)?.takeIf { it.isFinite() } }
        paint.textSize = 11 * density
        paint.color = Palette.SUB
        if (usable.isEmpty()) { canvas.drawText("Нет данных", 8 * density, 30 * density, paint); return }
        val minValue = usable.min()
        val maxValue = usable.max()
        val padding = ((maxValue - minValue) * 0.1).coerceAtLeast(0.5)
        val low = minValue - padding; val high = maxValue + padding
        val left = 62 * density; val top = 24 * density
        val right = width - 12 * density; val bottom = height - 30 * density
        if (right <= left || bottom <= top) return
        val end = (samples.lastOrNull()?.elapsedMs ?: 0L).coerceAtLeast(60_000L).toDouble()
        paint.strokeWidth = density
        for (i in 0..2) {
            val y = top + (bottom - top) * i / 2
            paint.color = Palette.TILE
            canvas.drawLine(left, y, right, y, paint)
            paint.color = Palette.SUB
            canvas.drawText("%.1f".format(high - (high - low) * i / 2), 0f, y + 4 * density, paint)
        }
        canvas.drawText(unit, left, 14 * density, paint)
        canvas.drawText("0 мин", left, height - 7 * density, paint)
        val label = "%.1f мин".format(end / 60_000)
        canvas.drawText(label, right - paint.measureText(label), height - 7 * density, paint)
        val path = Path()
        var previous: ChargePoint? = null
        for (p in samples) {
            val v = select(p)
            if (v == null || !v.isFinite()) { previous = null; continue }
            val x = left + (p.elapsedMs / end * (right - left)).toFloat()
            val y = bottom - ((v - low) / (high - low) * (bottom - top)).toFloat()
            val last = previous
            if (last != null && p.linked && p.elapsedMs - last.elapsedMs in 1..ChargeMath.MAX_GAP_MS) path.lineTo(x, y)
            else path.moveTo(x, y)
            paint.color = Palette.ACCENT; paint.style = Paint.Style.FILL
            canvas.drawCircle(x, y, 1.5f * density, paint)
            previous = p
        }
        paint.color = Palette.ACCENT; paint.style = Paint.Style.STROKE; paint.strokeWidth = 2 * density
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
    }
}

class HistoryPanel(
    private val activity: Activity,
    private val kit: UiKit,
    private val store: HistoryStore,
    private val execute: (() -> Unit) -> Unit,
    private val connected: () -> Boolean,
    private val targets: () -> List<Ecu>,
    private val capture: (Ecu, String) -> Unit,
    private val export: (String, String) -> Unit
) {
    private lateinit var chargeStatus: TextView
    private lateinit var chargeList: LinearLayout
    private lateinit var snapshotList: LinearLayout
    private lateinit var target: Spinner
    private lateinit var first: Spinner
    private lateinit var second: Spinner
    private var ecus = emptyList<Ecu>()
    private var snapshots = emptyList<ParameterSnapshot>()
    private val dateFormat get() = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
    private fun date(ms: Long) = dateFormat.format(Date(ms))
    private fun toast(message: String) = activity.runOnUiThread { if (!activity.isDestroyed) Toast.makeText(activity, message, Toast.LENGTH_LONG).show() }
    private fun background(block: () -> Unit) = execute {
        try { block() } catch (ex: Exception) { toast("История: ${ex.message}") }
    }
    private fun post(block: () -> Unit) = activity.runOnUiThread { if (!activity.isDestroyed) block() }

    fun build(): View {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(kit.dp(12), kit.dp(12), kit.dp(12), kit.dp(12))
        }
        val charge = kit.card("История зарядок")
        charge.addView(kit.note("Начните сессию перед зарядкой и завершите после неё. Сохраняются только измерения при подключённом адаптере. Держите приложение открытым; фоновая запись пока не поддерживается."))
        chargeStatus = kit.text("Загрузка…", 13f)
        charge.addView(chargeStatus)
        charge.addView(kit.row(
            kit.button("Начать", true) {
                if (!connected()) { toast("Сначала подключитесь к адаптеру"); return@button }
                askName("Название зарядки", "Зарядка ${date(System.currentTimeMillis())}") { title ->
                    background {
                        check(connected()) { "Подключитесь к адаптеру и дождитесь завершения текущей операции" }
                        store.start(title, System.currentTimeMillis(), SystemClock.elapsedRealtime())
                        refresh()
                    }
                }
            },
            kit.button("Завершить") { background { store.stop(System.currentTimeMillis()); refresh() } }
        ).apply { setPadding(0, kit.dp(8), 0, 0) })
        charge.addView(kit.button("Обновить историю") { refresh() })
        chargeList = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        charge.addView(chargeList); content.addView(charge)

        val snapshot = kit.card("Снимки параметров")
        snapshot.addView(kit.note("Снимок заново читает известные идентификаторы выбранного блока, включая сохранённые после скана. Запросы выполняются последовательно, обычный опрос и запись зарядки на это время прерываются."))
        target = Spinner(activity); snapshot.addView(target)
        snapshot.addView(kit.button("Сохранить снимок", true) {
            val ecu = ecus.getOrNull(target.selectedItemPosition) ?: return@button
            askName("Название снимка", "${ecu.short} · ${date(System.currentTimeMillis())}") { name -> capture(ecu, name) }
        })
        snapshot.addView(kit.note("A — исходное состояние, B — новое. Сравнивать можно снимки одного блока."))
        first = Spinner(activity); second = Spinner(activity)
        snapshot.addView(kit.text("Снимок A", 12f, Palette.SUB)); snapshot.addView(first)
        snapshot.addView(kit.text("Снимок B", 12f, Palette.SUB)); snapshot.addView(second)
        snapshot.addView(kit.button("Сравнить") {
            val a = snapshots.getOrNull(first.selectedItemPosition)
            val b = snapshots.getOrNull(second.selectedItemPosition)
            if (a == null || b == null) { toast("Сначала сохраните два снимка"); return@button }
            if (a.id == b.id) { toast("Выберите два разных снимка"); return@button }
            try {
                val report = "A: ${date(a.startedAt)}\nB: ${date(b.startedAt)}\n" + SnapshotDiff.compare(a, b)
                val view = kit.mono(12f).apply { text = report; setPadding(kit.dp(12), kit.dp(12), kit.dp(12), kit.dp(12)) }
                AlertDialog.Builder(activity).setTitle("Сравнение снимков")
                    .setView(ScrollView(activity).apply { addView(view) })
                    .setPositiveButton("Закрыть", null)
                    .setNeutralButton("Экспорт") { _, _ -> export("byd_comparison", report) }.show()
            } catch (ex: IllegalArgumentException) { toast(ex.message ?: "Несовместимые снимки") }
        })
        snapshotList = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        snapshot.addView(snapshotList); content.addView(snapshot)
        return ScrollView(activity).apply { addView(content) }
    }

    fun refresh() {
        post {
            val selected = ecus.getOrNull(target.selectedItemPosition)?.req
            ecus = targets()
            target.adapter = adapter(ecus.map { it.label })
            ecus.indexOfFirst { it.req == selected }.takeIf { it >= 0 }?.let { target.setSelection(it) }
        }
        background {
            val charges = store.charges().take(50).map { it.copy(points = it.points.toMutableList()) }
            val saved = store.snapshots()
            val current = store.current()
            val status = current?.let { "Идёт сессия «${it.title}»: ${it.points.size} измерений" } ?: "Сессия не запущена"
            val problem = store.problem
            post { render(charges, saved, status, current?.id, problem) }
        }
    }

    fun sampleStatus() {
        val s = store.current()
        val text = s?.let { "Идёт сессия «${it.title}»: ${it.points.size} измерений" } ?: "Сессия не запущена"
        post { chargeStatus.text = text }
    }

    private fun adapter(labels: List<String>) = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, labels)

    private fun render(charges: List<ChargeSession>, saved: List<ParameterSnapshot>, status: String, activeId: String?, problem: String?) {
        chargeStatus.text = status + (problem?.let { "\n$it" } ?: "")
        chargeList.removeAllViews()
        if (charges.isEmpty()) chargeList.addView(kit.note("Зарядок пока нет."))
        for (s in charges) {
            val box = kit.card(s.title)
            box.addView(kit.note(date(s.startedAt) + if (s.id == activeId) " · идёт запись" else " · завершена"))
            val summary = ChargeMath.summarize(s.points, s.durationMs)
            box.addView(kit.text("%.3f кВт·ч · пик %.1f кВт · %d измерений".format(summary.energyKwh, summary.peakKw, s.points.size), 13f))
            box.addView(kit.row(
                kit.button("Графики") { showCharge(s) },
                kit.button("Экспорт") { background { val json = store.chargeJson(s).toString(2); post { export("byd_charge", json) } } }
            ))
            if (s.id != activeId) box.addView(kit.button("Удалить") {
                confirmDelete(s.title) { background { store.deleteCharge(s.id); refresh() } }
            })
            chargeList.addView(box)
        }
        val a = snapshots.getOrNull(first.selectedItemPosition)?.id
        val b = snapshots.getOrNull(second.selectedItemPosition)?.id
        snapshots = saved
        val labels = saved.map { "${it.title} · ${date(it.startedAt)}" }
        first.adapter = adapter(labels); second.adapter = adapter(labels)
        if (saved.isNotEmpty()) {
            first.setSelection(saved.indexOfFirst { it.id == a }.takeIf { it >= 0 } ?: (saved.size - 1))
            second.setSelection(saved.indexOfFirst { it.id == b }.takeIf { it >= 0 } ?: 0)
        }
        snapshotList.removeAllViews()
        if (saved.isEmpty()) snapshotList.addView(kit.note("Снимков пока нет."))
        saved.forEach { s ->
            val box = kit.card(s.title)
            box.addView(kit.note("${date(s.startedAt)} · %03X · %d/%d ответов%s".format(s.requestId,
                s.readings.count { it.hex != null }, s.readings.size, if (s.partial) " · прерван" else "")))
            box.addView(kit.row(
                kit.button("Экспорт") { background { val json = store.snapshotJson(s).toString(2); post { export("byd_snapshot", json) } } },
                kit.button("Удалить") { confirmDelete(s.title) { background { store.deleteSnapshot(s.id); refresh() } } }
            ))
            snapshotList.addView(box)
        }
    }

    private fun showCharge(s: ChargeSession) {
        val summary = ChargeMath.summarize(s.points, s.durationMs)
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(kit.dp(14), kit.dp(8), kit.dp(14), kit.dp(8)) }
        val firstSoc = s.points.firstOrNull()?.soc; val lastSoc = s.points.lastOrNull()?.soc
        val soc = if (firstSoc != null && lastSoc != null) "SOC: %.2f → %.2f %%\n".format(firstSoc, lastSoc) else ""
        box.addView(kit.text(soc + "Получено батареей: %.3f кВт·ч\nПик: %.1f кВт\n".format(summary.energyKwh, summary.peakKw) +
            "Средняя мощность: ${summary.averageKw?.let { "%.1f кВт".format(it) } ?: "—"}\n" +
            "Учтено: %.1f мин; без данных: %.1f мин\n".format(summary.coveredMs / 60_000.0, summary.gapMs / 60_000.0) +
            "${s.endReason ?: "Сессия продолжается"}", 13f))
        box.addView(kit.note("Энергия — оценка по току и напряжению батареи, без потерь зарядного устройства. Мощность: плюс — заряд, минус — разряд. Пропуски не соединяются и не входят в энергию. Обновите историю для новых точек текущей сессии."))
        box.addView(ChargeChart(activity, s.points, "SOC, %") { it.soc }, LinearLayout.LayoutParams(MATCH_PARENT, kit.dp(180)))
        box.addView(ChargeChart(activity, s.points, "Мощность, кВт") { it.powerKw }, LinearLayout.LayoutParams(MATCH_PARENT, kit.dp(180)))
        box.addView(ChargeChart(activity, s.points, "Температура, °C") { it.tempC }, LinearLayout.LayoutParams(MATCH_PARENT, kit.dp(180)))
        box.addView(ChargeChart(activity, s.points, "Разброс ячеек, мВ") { it.dvMv }, LinearLayout.LayoutParams(MATCH_PARENT, kit.dp(180)))
        AlertDialog.Builder(activity).setTitle(s.title).setView(ScrollView(activity).apply { addView(box) })
            .setPositiveButton("Закрыть", null).show()
    }

    private fun askName(title: String, default: String, action: (String) -> Unit) {
        val edit = EditText(activity).apply { setText(default); isSingleLine = true; selectAll() }
        AlertDialog.Builder(activity).setTitle(title).setView(edit).setPositiveButton("Сохранить") { _, _ ->
            action(edit.text.toString().trim().take(120).ifEmpty { default })
        }.setNegativeButton("Отмена", null).show()
    }

    private fun confirmDelete(title: String, action: () -> Unit) {
        AlertDialog.Builder(activity).setTitle("Удалить запись?").setMessage(title)
            .setPositiveButton("Удалить") { _, _ -> action() }.setNegativeButton("Отмена", null).show()
    }
}
