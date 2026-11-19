package com.uriya.medreminder

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri

enum class SoundMode {
    /** ring with the chosen tone + vibrate */
    SOUND,
    /** no sound, vibrate only */
    VIBRATE,
    /** notification only: no sound, no vibration */
    SILENT;

    val label: String
        get() = when (this) {
            SOUND -> tr("צלצול", "Sound")
            VIBRATE -> tr("רטט בלבד", "Vibrate only")
            SILENT -> tr("שקט – התראה בלבד", "Silent – notification only")
        }
}

/** Reminder sound settings (saved on the phone). */
object SoundPrefs {
    private const val PREFS = "settings"
    private const val KEY_MODE = "sound_mode"
    private const val KEY_TONE = "sound_tone"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun mode(ctx: Context): SoundMode =
        runCatching { SoundMode.valueOf(prefs(ctx).getString(KEY_MODE, null) ?: "SOUND") }.getOrDefault(SoundMode.SOUND)

    /** null = the phone's default notification sound */
    fun tone(ctx: Context): Uri? = prefs(ctx).getString(KEY_TONE, null)?.let { Uri.parse(it) }

    fun setMode(ctx: Context, mode: SoundMode) {
        prefs(ctx).edit().putString(KEY_MODE, mode.name).apply()
        Notifier.createChannels(ctx)
    }

    fun setTone(ctx: Context, uri: Uri?) {
        prefs(ctx).edit().apply {
            if (uri == null) remove(KEY_TONE) else putString(KEY_TONE, uri.toString())
        }.apply()
        Notifier.createChannels(ctx)
    }

    fun toneTitle(ctx: Context): String {
        val uri = tone(ctx) ?: return tr("ברירת המחדל של הטלפון", "Phone default")
        return runCatching { RingtoneManager.getRingtone(ctx, uri)?.getTitle(ctx) }.getOrNull()
            ?: tr("צליל שנבחר", "Selected tone")
    }
}
