package com.uriya.medreminder

import android.app.Activity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Slider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.sp
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private fun open(ctx: Context, intent: Intent) {
    try {
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    // re-read system state whenever the user comes back from the system settings
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }

    val notifOk = remember(refresh) { Notifier.hasPermission(ctx) }
    val exactOk = remember(refresh) { Scheduler.canExact(ctx) }
    val batteryOk = remember(refresh) {
        ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) ?: true
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            val ok = runCatching {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(Repo.exportJson().toByteArray()) }
            }.isSuccess
            Toast.makeText(ctx, if (ok) tr("הגיבוי נשמר", "Backup saved") else tr("שמירת הגיבוי נכשלה", "Saving the backup failed"), Toast.LENGTH_SHORT).show()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            }.getOrNull()
            val ok = text != null && Repo.importJson(ctx, text)
            Toast.makeText(ctx, if (ok) tr("הנתונים שוחזרו", "Data restored") else tr("הקובץ לא תקין", "Invalid file"), Toast.LENGTH_SHORT).show()
        }
    }

    // reminder sound
    var soundMode by remember { mutableStateOf(SoundPrefs.mode(ctx)) }
    var toneTitle by remember(Lang.current) { mutableStateOf(SoundPrefs.toneTitle(ctx)) }
    val tonePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val uri = res.data?.let {
                IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            }
            // "Default" in the picker returns the default URI → store as null (follows the phone)
            SoundPrefs.setTone(ctx, if (uri == null || RingtoneManager.isDefault(uri)) null else uri)
            toneTitle = SoundPrefs.toneTitle(ctx)
        }
    }

    // background photo
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !ThemePrefs.importPhoto(ctx, uri)) {
            Toast.makeText(ctx, tr("לא הצלחתי לפתוח את התמונה", "Couldn't open that picture"), Toast.LENGTH_SHORT).show()
        }
    }

    var importPlan by remember { mutableStateOf<MedisafeImport.Plan?>(null) }
    val medisafeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            }.getOrNull()
            val plan = text?.let { runCatching { MedisafeImport.plan(it, Repo.meds.value) }.getOrNull() }
            if (plan == null || plan.meds.isEmpty()) {
                Toast.makeText(ctx, tr("לא נמצאו בקובץ נתוני תרופות של Medisafe", "No Medisafe medication data found in the file"), Toast.LENGTH_LONG).show()
            } else {
                importPlan = plan
            }
        }
    }

    importPlan?.let { plan ->
        MedisafeImportDialog(
            plan = plan,
            onDismiss = { importPlan = null },
            onConfirm = {
                importPlan = null
                val added = MedisafeImport.apply(ctx, plan)
                Toast.makeText(ctx, tr("הייבוא הושלם: נוספו $added רשומות", "Import complete: $added records added"), Toast.LENGTH_LONG).show()
            }
        )
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionTitle("שפה / Language", Modifier.padding(top = 0.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppLang.entries.forEach { l ->
                FilterChip(
                    selected = Lang.current == l,
                    onClick = { Lang.set(ctx, l) },
                    label = { Text(l.title) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        SectionTitle(tr("רקע", "Background"))
        BackgroundPicker(onPickPhoto = { photoPicker.launch(arrayOf("image/*")) })

        SectionTitle(tr("כדי שהתזכורות יגיעו בזמן", "Making sure reminders arrive on time"))

        StatusRow(
            ok = notifOk,
            title = tr("הרשאת התראות", "Notification permission"),
            okText = tr("מאושר", "Allowed"),
            badText = tr("ההתראות חסומות — לא תקבל תזכורות", "Notifications are blocked — you won't get reminders"),
            button = tr("פתח הגדרות התראות", "Open notification settings")
        ) {
            open(ctx, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
        }

        StatusRow(
            ok = exactOk,
            title = tr("התראות בשעה מדויקת", "Exact-time alarms"),
            okText = tr("מאושר", "Allowed"),
            badText = tr("בלי זה התזכורת עלולה להתעכב בכמה דקות", "Without this, reminders may be a few minutes late"),
            button = tr("אשר שעונים מדויקים", "Allow exact alarms")
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                open(ctx, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + ctx.packageName)))
            }
        }

        StatusRow(
            ok = batteryOk,
            title = tr("חיסכון בסוללה", "Battery optimization"),
            okText = tr("האפליקציה לא מוגבלת", "The app is not restricted"),
            badText = tr("בחלק מהמכשירים (שיאומי, סמסונג וכו׳) חיסכון בסוללה מעכב תזכורות. מומלץ לבחור \"ללא הגבלה\".", "On some phones (Xiaomi, Samsung, etc.) battery saving delays reminders. Choose \"Unrestricted\"."),
            button = tr("פתח הגדרות סוללה", "Open battery settings")
        ) {
            open(ctx, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }

        SectionTitle(tr("צליל התזכורת", "Reminder sound"))
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SoundMode.entries.forEach { m ->
                        FilterChip(
                            selected = soundMode == m,
                            onClick = {
                                soundMode = m
                                SoundPrefs.setMode(ctx, m)
                            },
                            label = { Text(m.label, maxLines = 2) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Text(
                    when (soundMode) {
                        SoundMode.SOUND -> tr("התזכורת תצלצל ותרטוט", "Reminders ring and vibrate")
                        SoundMode.VIBRATE -> tr("בלי צלצול – רק רטט והתראה", "No sound – vibration and notification only")
                        SoundMode.SILENT -> tr("בלי צלצול ובלי רטט – רק התראה על המסך", "No sound, no vibration – on-screen notification only")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
                if (soundMode == SoundMode.SOUND) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(tr("צלצול", "Tone"), fontWeight = FontWeight.SemiBold)
                            Text(
                                toneTitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(onClick = {
                            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
                                .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, tr("בחירת צלצול לתזכורת", "Choose a reminder tone"))
                                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                                .putExtra(
                                    RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                                    SoundPrefs.tone(ctx) ?: Settings.System.DEFAULT_NOTIFICATION_URI
                                )
                            try {
                                tonePicker.launch(intent)
                            } catch (_: ActivityNotFoundException) {
                                Toast.makeText(ctx, tr("לא נמצא בוחר צלצולים בטלפון", "No tone picker found on this phone"), Toast.LENGTH_SHORT).show()
                            }
                        }) { Text(tr("החלפה", "Change")) }
                    }
                }
            }
        }

        FilledTonalButton(onClick = { Notifier.test(ctx) }, modifier = Modifier.fillMaxWidth()) {
            Text(tr("שלח התראת בדיקה", "Send a test notification"))
        }

        SectionTitle(tr("ייבוא מ-Medisafe", "Import from Medisafe"))
        Text(
            tr("ב-Medisafe: דוחות ← ייצוא לקובץ CSV. כאן בוחרים את הקובץ, והתרופות וההיסטוריה ייכנסו לאפליקציה. רשומות שכבר קיימות לא ישוכפלו.", "In Medisafe: Reports → export to CSV. Pick the file here and the medications and history will be added. Existing records are not duplicated."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FilledTonalButton(
            onClick = {
                medisafeLauncher.launch(
                    arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/vnd.ms-excel", "application/octet-stream", "*/*")
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(tr("ייבוא קובץ דוח של Medisafe (CSV)", "Import a Medisafe report (CSV)")) }

        SectionTitle(tr("גיבוי ושחזור", "Backup & restore"))
        Text(
            tr("כל הנתונים נשמרים רק בטלפון שלך, בלי אינטרנט ובלי חשבון. כדאי לשמור גיבוי מדי פעם.", "All data stays only on your phone — no internet, no account. Save a backup from time to time."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { exportLauncher.launch("medreminder-backup-${LocalDate.now()}.json") },
                modifier = Modifier.weight(1f)
            ) { Text(tr("שמירת גיבוי", "Save backup")) }
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                modifier = Modifier.weight(1f)
            ) { Text(tr("שחזור מגיבוי", "Restore backup")) }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            tr("תרופה בזמן · גרסה 1.8\nהאפליקציה היא כלי תזכורת בלבד ואינה מחליפה ייעוץ רפואי.", "MedTime · version 1.8\nThis app is a reminder tool only and does not replace medical advice."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StatusRow(
    ok: Boolean,
    title: String,
    okText: String,
    badText: String,
    button: String,
    onFix: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (ok) "✅" else "⚠️")
                Text("  $title", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            }
            Text(
                if (ok) okText else badText,
                style = MaterialTheme.typography.bodySmall,
                color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant else statusColor(DoseStatus.MISSED),
                modifier = Modifier.padding(top = 4.dp)
            )
            if (!ok) {
                FilledTonalButton(onClick = onFix, modifier = Modifier.padding(top = 8.dp)) { Text(button) }
            }
        }
    }
}

@Composable
private fun MedisafeImportDialog(plan: MedisafeImport.Plan, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val f = DateTimeFormatter.ofPattern("dd/MM/yy")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("ייבוא מ-Medisafe", "Import from Medisafe")) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(tr("נמצאו ${plan.totalRows} רשומות של ${plan.meds.size} תרופות:", "Found ${plan.totalRows} records for ${plan.meds.size} medications:"))
                plan.meds.forEach { m ->
                    Column {
                        Text(
                            "💊 ${m.name}  " + if (m.existing != null) tr("(קיימת — תתעדכן)", "(existing — will be updated)") else tr("(חדשה)", "(new)"),
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            tr("שעות: ", "Times: ") + m.times.joinToString(", ") + " · ${m.from.format(f)}–${m.to.format(f)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            tr("נלקחו ${m.taken} · דולגו ${m.skipped} · הוחמצו ${m.missed}", "Taken ${m.taken} · Skipped ${m.skipped} · Missed ${m.missed}"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (plan.ignoredRows > 0) {
                    Text(
                        tr("${plan.ignoredRows} שורות לא זוהו ולא ייובאו", "${plan.ignoredRows} rows were not recognized and won't be imported"),
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor(DoseStatus.MISSED)
                    )
                }
                Text(
                    tr("תרופות חדשות ייווצרו עם השעות שלמעלה. אפשר לערוך אחר כך מינון, סוג ומלאי במסך \"תרופות\".", "New medications will be created with the times above. You can edit dose, type and stock later under \"Medications\"."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text(tr("ייבוא", "Import")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("ביטול", "Cancel")) } }
    )
}

@Composable
private fun BackgroundPicker(onPickPhoto: () -> Unit) {
    val ctx = LocalContext.current
    val current = ThemePrefs.background
    val photoThumb = remember(ThemePrefs.photoVersion) { ThemePrefs.loadPhoto(ctx) }

    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
    ) {
        AppBackground.entries.forEach { bg ->
            val selected = current == bg
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(width = 62.dp, height = 84.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .then(
                            when (bg) {
                                AppBackground.DEFAULT -> Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                                AppBackground.PHOTO -> Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                                else -> Modifier.background(bg.brush)
                            }
                        )
                        .border(
                            width = if (selected) 3.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(14.dp)
                        )
                        .clickable {
                            if (bg == AppBackground.PHOTO && photoThumb == null) onPickPhoto()
                            else ThemePrefs.setBackground(ctx, bg)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (bg == AppBackground.PHOTO) {
                        if (photoThumb != null) {
                            Image(
                                bitmap = photoThumb,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Text("🖼️", fontSize = 26.sp)
                        }
                    }
                    if (selected) {
                        Box(
                            Modifier.size(26.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) { Text("✓", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold) }
                    }
                }
                Text(bg.label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }

    if (current == AppBackground.PHOTO) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(tr("כמה רואים את התמונה", "How visible the photo is"), fontWeight = FontWeight.SemiBold)
                Slider(
                    value = ThemePrefs.photoVisibility,
                    onValueChange = { ThemePrefs.setPhotoVisibility(ctx, it) },
                    valueRange = 0.1f..0.9f
                )
                Text(
                    tr("עדין יותר = קל יותר לקרוא את הטקסט", "Fainter = easier to read the text"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(onClick = onPickPhoto, modifier = Modifier.padding(top = 8.dp)) {
                    Text(tr("בחירת תמונה אחרת", "Choose another photo"))
                }
            }
        }
    }
}
