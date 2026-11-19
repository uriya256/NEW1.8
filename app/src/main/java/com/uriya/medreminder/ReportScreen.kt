package com.uriya.medreminder

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private const val YEAR_MODE = -1

private val RESOLVED = setOf(DoseStatus.TAKEN, DoseStatus.SKIPPED, DoseStatus.MISSED)

private fun pct(taken: Int, total: Int): String = if (total == 0) "—" else "${(taken * 100f / total).toInt()}%"

@Composable
fun ReportScreen(now: LocalDateTime, meds: List<Medication>, logs: Map<String, LogEntry>) {
    val today = now.toLocalDate()
    // resolved doses (taken / skipped / missed) for the last 30 days, newest first
    val byDay: List<Pair<LocalDate, List<Dose>>> = remember(meds, logs, now) {
        (0L until 30L).map { off ->
            val d = today.minusDays(off)
            d to computeDoses(meds, logs, d, now).filter { it.status in RESOLVED }
        }
    }
    val last7 = byDay.take(7).flatMap { it.second }
    val last30 = byDay.flatMap { it.second }

    val ctx = LocalContext.current
    // 7 / 30 / 90 / 365 = last N days; YEAR_MODE = calendar year (Jan–Dec)
    var pdfDays by rememberSaveable { mutableIntStateOf(30) }
    var pdfYear by rememberSaveable { mutableIntStateOf(LocalDate.now().year) }
    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) {
            val ok = runCatching {
                ctx.contentResolver.openOutputStream(uri)?.use { out ->
                    val today = LocalDate.now()
                    if (pdfDays == YEAR_MODE) {
                        PdfReport.write(
                            out, Repo.meds.value, Repo.logs.value,
                            from = LocalDate.of(pdfYear, 1, 1),
                            to = LocalDate.of(pdfYear, 12, 31),
                            periodLabel = tr("שנת $pdfYear", "Year $pdfYear")
                        )
                    } else {
                        PdfReport.write(
                            out, Repo.meds.value, Repo.logs.value,
                            from = today.minusDays((pdfDays - 1).toLong()),
                            to = today,
                            periodLabel = if (pdfDays >= 365) tr("שנה אחרונה", "Last 12 months") else tr("$pdfDays ימים אחרונים", "Last $pdfDays days")
                        )
                    }
                } ?: error("no stream")
            }.isSuccess
            if (!ok) {
                Toast.makeText(ctx, tr("יצירת ה-PDF נכשלה", "Creating the PDF failed"), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(ctx, tr("הדוח נשמר", "Report saved"), Toast.LENGTH_SHORT).show()
                try {
                    ctx.startActivity(
                        Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, "application/pdf")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (_: ActivityNotFoundException) {
                }
            }
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(tr("📄 דוח PDF (למשל לרופא)", "📄 PDF report (e.g. for your doctor)"), fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        listOf(
                            YEAR_MODE to tr("שנה מלאה (ינואר–דצמבר)", "Full year (Jan–Dec)"),
                            7 to tr("שבוע", "Week"),
                            30 to tr("חודש", "Month"),
                            90 to tr("3 חודשים", "3 months"),
                            365 to tr("12 חודשים אחרונים", "Last 12 months")
                        ).forEach { (n, label) ->
                            FilterChip(
                                selected = pdfDays == n,
                                onClick = { pdfDays = n },
                                label = { Text(label) }
                            )
                        }
                    }
                    if (pdfDays == YEAR_MODE) {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            IconButton(onClick = { pdfYear-- }) {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = tr("שנה קודמת", "Previous year"))
                            }
                            Text(
                                tr("שנת $pdfYear", "Year $pdfYear"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 12.dp)
                            )
                            IconButton(
                                onClick = { pdfYear++ },
                                enabled = pdfYear < LocalDate.now().year
                            ) {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = tr("שנה הבאה", "Next year"))
                            }
                        }
                        Text(
                            tr("1 בינואר – 31 בדצמבר $pdfYear", "January 1 – December 31, $pdfYear"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val name = if (pdfDays == YEAR_MODE) tr("דוח-תרופות-שנת-$pdfYear.pdf", "medication-report-$pdfYear.pdf") else tr("דוח-תרופות-${LocalDate.now()}.pdf", "medication-report-${LocalDate.now()}.pdf")
                            pdfLauncher.launch(name)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(tr("הפקת דוח PDF", "Create PDF report")) }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(tr("7 ימים אחרונים", "Last 7 days"), last7, Modifier.weight(1f))
                StatCard(tr("30 ימים אחרונים", "Last 30 days"), last30, Modifier.weight(1f))
            }
        }

        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(tr("היענות לפי יום", "Adherence by day"), fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.fillMaxWidth().height(140.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        byDay.take(7).reversed().forEach { (day, list) ->
                            val total = list.size
                            val taken = list.count { it.status == DoseStatus.TAKEN }
                            val frac = if (total == 0) 0f else taken / total.toFloat()
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(34.dp)) {
                                Text(if (total == 0) "" else pct(taken, total), style = MaterialTheme.typography.labelSmall)
                                Box(
                                    Modifier.width(22.dp).height(100.dp).clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.BottomCenter
                                ) {
                                    Box(
                                        Modifier.fillMaxWidth().fillMaxHeight(frac.coerceAtLeast(0.001f))
                                            .background(
                                                if (frac >= 0.8f) statusColor(DoseStatus.TAKEN)
                                                else if (frac >= 0.5f) statusColor(DoseStatus.DUE)
                                                else statusColor(DoseStatus.MISSED)
                                            )
                                    )
                                }
                                Text(dayShort(day.dayOfWeek.value), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }

        if (meds.isNotEmpty()) {
            item { SectionTitle(tr("לפי תרופה (30 יום)", "By medication (30 days)")) }
            items(meds.sortedBy { it.name }, key = { "m${it.id}" }) { m ->
                val list = last30.filter { it.med.id == m.id }
                val taken = list.count { it.status == DoseStatus.TAKEN }
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        MedAvatar(m, 38.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m.name, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { if (list.isEmpty()) 0f else taken / list.size.toFloat() },
                                modifier = Modifier.fillMaxWidth().height(8.dp),
                                color = medColor(m.colorIndex),
                                strokeCap = StrokeCap.Round
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Text("${pct(taken, list.size)}\n$taken/${list.size}", textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        val history = byDay.take(7).flatMap { (_, l) -> l.sortedByDescending { it.time } }
        if (history.isNotEmpty()) {
            item {
                SectionTitle(tr("היסטוריה (שבוע אחרון)", "History (last week)"))
                Text(
                    tr("לחיצה על מנה שנלקחה מאפשרת לשנות את שעת הנטילה", "Tap a taken dose to change when it was taken"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items(history, key = { "h" + it.key }) { d ->
                val isTaken = d.status == DoseStatus.TAKEN
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = isTaken) {
                            val current = d.loggedAt?.let {
                                Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalTime()
                            } ?: LocalTime.parse(d.time)
                            pickTakenTime(ctx, d.date, current) { at ->
                                Repo.setTakenTime(ctx, d.med.id, d.date.toString(), d.time, at)
                            }
                        }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(d.med.form.emoji)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.med.name, fontWeight = FontWeight.Medium)
                        Text(
                            d.date.format(DateTimeFormatter.ofPattern("dd/MM")) + " · " + d.time +
                                (if (d.status == DoseStatus.TAKEN && d.loggedAt != null) tr(" · נלקח ב-${takenText(d.date, d.loggedAt)} ✎", " · taken at ${takenText(d.date, d.loggedAt)} ✎") else ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    StatusChip(d.status)
                }
            }
        }
    }
}

@Composable
private fun StatCard(title: String, doses: List<Dose>, modifier: Modifier) {
    val total = doses.size
    val taken = doses.count { it.status == DoseStatus.TAKEN }
    val missed = doses.count { it.status == DoseStatus.MISSED }
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = modifier
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(pct(taken, total), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(tr("$taken נלקחו · $missed הוחמצו", "$taken taken · $missed missed"), style = MaterialTheme.typography.bodySmall)
        }
    }
}
