package com.uriya.medreminder

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import java.io.OutputStream
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Builds a printable Hebrew (RTL) PDF summary with the built-in Android PdfDocument. */
object PdfReport {
    private const val PAGE_W = 595 // A4 in points
    private const val PAGE_H = 842
    private const val MARGIN = 36f
    private const val CONTENT_W = PAGE_W - 2 * MARGIN

    private val DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val SHORT = DateTimeFormatter.ofPattern("dd/MM")
    private val DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

    private val C_TEXT = Color.rgb(30, 33, 40)
    private val C_MUTED = Color.rgb(110, 115, 125)
    private val C_PRIMARY = Color.rgb(47, 111, 237)
    private val C_HEADER_BG = Color.rgb(226, 234, 252)
    private val C_ROW_ALT = Color.rgb(246, 248, 252)
    private val C_LINE = Color.rgb(215, 220, 230)

    private fun statusArgb(s: DoseStatus): Int = when (s) {
        DoseStatus.TAKEN -> Color.rgb(36, 140, 85)
        DoseStatus.SKIPPED -> Color.rgb(120, 122, 132)
        DoseStatus.MISSED -> Color.rgb(205, 50, 55)
        DoseStatus.DUE -> Color.rgb(200, 120, 0)
        DoseStatus.UPCOMING -> C_PRIMARY
    }

    private class Writer(private val doc: PdfDocument) {
        private var pageNo = 0
        private var page: PdfDocument.Page? = null
        lateinit var canvas: Canvas
        var y = MARGIN

        fun newPage() {
            page?.let { finishPage(it) }
            pageNo++
            val p = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            page = p
            canvas = p.canvas
            y = MARGIN
        }

        private fun finishPage(p: PdfDocument.Page) {
            val l = layout(tr("עמוד $pageNo · תרופה בזמן", "Page $pageNo · MedTime"), 8f, false, C_MUTED, CONTENT_W, Layout.Alignment.ALIGN_CENTER)
            draw(l, MARGIN, PAGE_H - MARGIN + 10f)
            doc.finishPage(p)
        }

        fun close() {
            page?.let { finishPage(it) }
            page = null
        }

        fun ensure(h: Float) {
            if (y + h > PAGE_H - MARGIN - 6f) newPage()
        }

        fun layout(
            text: String, size: Float, bold: Boolean, color: Int, width: Float,
            align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL
        ): StaticLayout {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size
                this.color = color
                typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
            return StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt().coerceAtLeast(10))
                .setAlignment(align)
                .setTextDirection(if (Lang.isHe) TextDirectionHeuristics.RTL else TextDirectionHeuristics.LTR)
                .setLineSpacing(0f, 1.1f)
                .build()
        }

        fun draw(l: StaticLayout, x: Float, top: Float) {
            canvas.save()
            canvas.translate(x, top)
            l.draw(canvas)
            canvas.restore()
        }

        fun text(t: String, size: Float = 10f, bold: Boolean = false, color: Int = C_TEXT, after: Float = 4f) {
            val l = layout(t, size, bold, color, CONTENT_W)
            ensure(l.height.toFloat())
            draw(l, MARGIN, y)
            y += l.height + after
        }

        fun heading(t: String) {
            ensure(40f)
            y += 10f
            text(t, 13f, true, C_PRIMARY, 2f)
            val p = Paint().apply { color = C_PRIMARY; strokeWidth = 1.2f }
            canvas.drawLine(MARGIN, y, PAGE_W - MARGIN, y, p)
            y += 6f
        }

        /**
         * One table row. Cells follow the language direction (Hebrew: first cell on the right).
         * weights: relative column widths.
         */
        fun row(
            cells: List<String>,
            weights: List<Float>,
            header: Boolean = false,
            alt: Boolean = false,
            colors: List<Int?> = emptyList(),
            onNewPage: (() -> Unit)? = null
        ) {
            val total = weights.sum()
            val pad = 4f
            val widths = weights.map { it / total * CONTENT_W }
            val layouts = cells.mapIndexed { i, c ->
                layout(
                    c, if (header) 9f else 9f, header,
                    colors.getOrNull(i) ?: C_TEXT, widths[i] - 2 * pad
                )
            }
            val h = (layouts.maxOf { it.height } + 2 * pad)
            if (y + h > PAGE_H - MARGIN - 6f) {
                newPage()
                onNewPage?.invoke()
            }
            val bg = when {
                header -> C_HEADER_BG
                alt -> C_ROW_ALT
                else -> null
            }
            if (bg != null) {
                canvas.drawRect(MARGIN, y, PAGE_W - MARGIN, y + h, Paint().apply { color = bg })
            }
            if (Lang.isHe) {
                // Hebrew: first column on the right
                var right = PAGE_W - MARGIN
                layouts.forEachIndexed { i, l ->
                    draw(l, right - widths[i] + pad, y + pad)
                    right -= widths[i]
                }
            } else {
                // English: first column on the left
                var left = MARGIN
                layouts.forEachIndexed { i, l ->
                    draw(l, left + pad, y + pad)
                    left += widths[i]
                }
            }
            canvas.drawLine(MARGIN, y + h, PAGE_W - MARGIN, y + h, Paint().apply { color = C_LINE; strokeWidth = 0.6f })
            y += h
        }
    }

    private fun pct(a: Int, b: Int) = if (b == 0) "—" else "${(a * 100f / b).toInt()}%"

    fun write(
        out: OutputStream,
        meds: List<Medication>,
        logs: Map<String, LogEntry>,
        from: LocalDate,
        to: LocalDate,
        /** e.g. "Year 2026" or "Last 30 days" (already translated) */
        periodLabel: String,
        now: LocalDateTime = LocalDateTime.now()
    ) {
        val today = now.toLocalDate()
        // nothing is expected in the future: data runs until today at most
        val dataTo = if (to.isAfter(today)) today else to
        val resolved = setOf(DoseStatus.TAKEN, DoseStatus.SKIPPED, DoseStatus.MISSED)

        // all resolved doses in the period, oldest first (January → December)
        val doses: List<Dose> = generateSequence(from) { it.plusDays(1) }
            .takeWhile { !it.isAfter(dataTo) }
            .flatMap { d -> computeDoses(meds, logs, d, now).filter { it.status in resolved } }
            .toList()
        val taken = doses.filter { it.status == DoseStatus.TAKEN }
        val skipped = doses.count { it.status == DoseStatus.SKIPPED }
        val missed = doses.count { it.status == DoseStatus.MISSED }
        val delays = taken.mapNotNull { d ->
            d.loggedAt?.let { at ->
                val t = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDateTime()
                Duration.between(d.dateTime, t).toMinutes()
            }
        }
        val onTime = delays.count { kotlin.math.abs(it) <= 30 }
        val avgLate = delays.filter { it > 0 }.let { if (it.isEmpty()) 0L else it.sum() / it.size }

        val doc = PdfDocument()
        val w = Writer(doc)
        w.newPage()

        // ---- title
        w.text(tr("דוח נטילת תרופות", "Medication Intake Report"), 20f, true, C_PRIMARY, 2f)
        w.text(
            "$periodLabel · ${from.format(DATE)} – ${to.format(DATE)}" +
                (if (dataTo.isBefore(to)) tr(" (נתונים עד ", " (data until ") + dataTo.format(DATE) + ")" else "") +
                tr(" · הופק ב-", " · Generated ") + now.format(DATE_TIME),
            9f, false, C_MUTED, 8f
        )

        // ---- summary
        w.heading(tr("סיכום", "Summary"))
        w.text(tr("היענות כוללת: ${pct(taken.size, doses.size)}", "Overall adherence: ${pct(taken.size, doses.size)}"), 12f, true)
        w.text(tr("נלקחו ${taken.size} מתוך ${doses.size} מנות · דולגו $skipped · הוחמצו $missed", "Taken ${taken.size} of ${doses.size} doses · Skipped $skipped · Missed $missed"))
        if (delays.isNotEmpty()) {
            w.text(tr("נלקחו בזמן (עד 30 דקות מהשעה המתוכננת): ${pct(onTime, delays.size)} · איחור ממוצע כשהיה איחור: $avgLate דק׳", "On time (within 30 min of schedule): ${pct(onTime, delays.size)} · Average delay when late: $avgLate min"))
        }

        // ---- monthly breakdown (for periods longer than two months)
        if (Duration.between(from.atStartOfDay(), to.atStartOfDay()).toDays() > 60) {
            w.heading(tr("היענות לפי חודש", "Adherence by month"))
            val cw = listOf(1.6f, 1f, 1f, 1f, 1.2f)
            val header = listOf(tr("חודש", "Month"), tr("נלקחו", "Taken"), tr("דולגו", "Skipped"), tr("הוחמצו", "Missed"), tr("היענות", "Adherence"))
            w.row(header, cw, header = true)
            val monthFmt = DateTimeFormatter.ofPattern("LLLL yyyy", Lang.locale)
            val byMonth = doses.groupBy { YearMonth.from(it.date) }
            // every month of the period, in order, including months without data
            generateSequence(YearMonth.from(from)) { it.plusMonths(1) }
                .takeWhile { !it.isAfter(YearMonth.from(to)) }
                .forEachIndexed { i, ym ->
                    val list = byMonth[ym].orEmpty()
                    val t = list.count { it.status == DoseStatus.TAKEN }
                    val future = ym.isAfter(YearMonth.from(today))
                    w.row(
                        listOf(
                            ym.format(monthFmt),
                            if (list.isEmpty()) "—" else t.toString(),
                            if (list.isEmpty()) "—" else list.count { it.status == DoseStatus.SKIPPED }.toString(),
                            if (list.isEmpty()) "—" else list.count { it.status == DoseStatus.MISSED }.toString(),
                            when {
                                future -> tr("עוד לא הגיע", "Not yet")
                                list.isEmpty() -> tr("אין נתונים", "No data")
                                else -> pct(t, list.size)
                            }
                        ),
                        cw, alt = i % 2 == 1,
                        colors = if (list.isEmpty()) List(5) { C_MUTED } else emptyList(),
                        onNewPage = { w.row(header, cw, header = true) }
                    )
                }
        }

        // ---- medications
        val activeMeds = meds.filter { it.active }.sortedBy { it.name }
        w.heading(tr("התרופות הפעילות", "Active medications"))
        if (activeMeds.isEmpty()) {
            w.text(tr("אין תרופות פעילות", "No active medications"), color = C_MUTED)
        } else {
            val cw = listOf(2.2f, 1.4f, 1.6f, 1.6f, 1.5f, 1.0f)
            val header = listOf(tr("תרופה", "Medication"), tr("מינון", "Dose"), tr("שעות", "Times"), tr("ימים", "Days"), tr("הנחיות", "Instructions"), tr("מלאי", "Stock"))
            w.row(header, cw, header = true)
            activeMeds.forEachIndexed { i, m ->
                val doseText = listOf(m.dose, if (m.perDose > 1) tr("${m.perDose} יח׳", "${m.perDose} units") else "")
                    .filter { it.isNotBlank() }.joinToString(" · ")
                val notes = listOf(if (m.food != FoodRule.NONE) m.food.label else "", m.notes)
                    .filter { it.isNotBlank() }.joinToString(" · ")
                w.row(
                    listOf(
                        "${m.name} (${m.form.label})",
                        doseText.ifBlank { "—" },
                        m.times.joinToString(", "),
                        daysText(m.days),
                        notes.ifBlank { "—" },
                        if (m.tracksStock) m.stock.toString() else "—"
                    ),
                    cw, alt = i % 2 == 1,
                    onNewPage = { w.row(header, cw, header = true) }
                )
            }
        }

        // ---- adherence per medication
        w.heading(tr("היענות לפי תרופה", "Adherence by medication"))
        val byMed = doses.groupBy { it.med.id }
        if (byMed.isEmpty()) {
            w.text(tr("אין נתונים לתקופה הזו", "No data for this period"), color = C_MUTED)
        } else {
            val cw = listOf(2.6f, 1f, 1f, 1f, 1.2f)
            val header = listOf(tr("תרופה", "Medication"), tr("נלקחו", "Taken"), tr("דולגו", "Skipped"), tr("הוחמצו", "Missed"), tr("היענות", "Adherence"))
            w.row(header, cw, header = true)
            byMed.values.sortedBy { it.first().med.name }.forEachIndexed { i, list ->
                val t = list.count { it.status == DoseStatus.TAKEN }
                w.row(
                    listOf(
                        list.first().med.name,
                        t.toString(),
                        list.count { it.status == DoseStatus.SKIPPED }.toString(),
                        list.count { it.status == DoseStatus.MISSED }.toString(),
                        pct(t, list.size)
                    ),
                    cw, alt = i % 2 == 1,
                    onNewPage = { w.row(header, cw, header = true) }
                )
            }
        }

        // ---- daily log
        w.heading(tr("יומן נטילות", "Intake log"))
        if (doses.isEmpty()) {
            w.text(tr("אין נתונים לתקופה הזו", "No data for this period"), color = C_MUTED)
        } else {
            val cw = listOf(1.1f, 0.9f, 2.4f, 1f, 1.2f)
            val header = listOf(tr("תאריך", "Date"), tr("שעה מתוכננת", "Scheduled"), tr("תרופה", "Medication"), tr("סטטוס", "Status"), tr("שעת נטילה", "Taken at"))
            w.row(header, cw, header = true)
            var lastDate: LocalDate? = null
            var alt = false
            doses.forEach { d ->
                if (d.date != lastDate) {
                    alt = !alt
                    lastDate = d.date
                }
                w.row(
                    listOf(
                        d.date.format(SHORT) + " " + dayShort(d.date.dayOfWeek.value),
                        d.time,
                        d.med.name + (if (d.med.dose.isNotBlank()) " · ${d.med.dose}" else ""),
                        d.status.label,
                        if (d.status == DoseStatus.TAKEN && d.loggedAt != null) takenText(d.date, d.loggedAt) else "—"
                    ),
                    cw, alt = alt,
                    colors = listOf(null, null, null, statusArgb(d.status), null),
                    onNewPage = { w.row(header, cw, header = true) }
                )
            }
        }

        w.y += 14f
        w.text(tr("הדוח הופק מאפליקציית \"תרופה בזמן\" ומבוסס על הסימונים של המשתמש.", "Generated by the MedTime app, based on the user's own records."), 8f, false, C_MUTED)

        w.close()
        doc.writeTo(out)
        doc.close()
    }
}
