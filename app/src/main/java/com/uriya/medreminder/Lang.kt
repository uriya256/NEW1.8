package com.uriya.medreminder

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

enum class AppLang(val code: String, val title: String) {
    HE("he", "עברית"),
    EN("en", "English")
}

/**
 * App language. Backed by Compose state, so every screen updates as soon as it changes.
 * Also used outside the UI (notifications, PDF).
 */
object Lang {
    private const val PREFS = "settings"
    private const val KEY = "lang"
    private var loaded = false

    var current by mutableStateOf(AppLang.HE)
        private set

    val isHe: Boolean get() = current == AppLang.HE
    val locale: Locale get() = if (isHe) HE else Locale.ENGLISH

    fun init(ctx: Context) {
        if (loaded) return
        loaded = true
        val saved = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        current = AppLang.entries.firstOrNull { it.code == saved } ?: run {
            // first launch: follow the phone's language
            val sys = Locale.getDefault().language
            if (sys == "he" || sys == "iw") AppLang.HE else AppLang.EN
        }
    }

    fun set(ctx: Context, lang: AppLang) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, lang.code).apply()
        current = lang
        // refresh notification channel names and anything already scheduled
        Notifier.createChannels(ctx)
    }
}

/** Picks the Hebrew or English text according to the app language. */
fun tr(he: String, en: String): String = if (Lang.isHe) he else en
