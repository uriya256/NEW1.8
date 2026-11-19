package com.uriya.medreminder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import java.io.File

/** Background themes. Each one also sets the app's main color. */
enum class AppBackground(
    private val he: String,
    private val en: String,
    val colors: List<Long>,
    val primary: Long,
    /** null = follow the phone's dark/light mode */
    val dark: Boolean?
) {
    DEFAULT("רגיל", "Plain", listOf(0xFFF4F6FB, 0xFFF4F6FB), 0xFF2F6FED, null),
    SKY("שמיים", "Sky", listOf(0xFFCFE3FF, 0xFFEAF3FF, 0xFFF7FAFF), 0xFF2F6FED, false),
    MINT("מנטה", "Mint", listOf(0xFFCDF3E4, 0xFFE9FAF3, 0xFFF6FDFA), 0xFF0E9A85, false),
    SUNSET("שקיעה", "Sunset", listOf(0xFFFFD3B8, 0xFFFFE6D6, 0xFFFCE4EF), 0xFFE0583A, false),
    LAVENDER("לבנדר", "Lavender", listOf(0xFFE2D6FF, 0xFFF0EAFF, 0xFFFAF7FF), 0xFF7446D6, false),
    ROSE("ורוד", "Rose", listOf(0xFFFFD6E6, 0xFFFFEAF2, 0xFFFFF7FA), 0xFFD02F69, false),
    SAND("חול", "Sand", listOf(0xFFF3E6CC, 0xFFF9F1E2, 0xFFFDFAF3), 0xFFB7791F, false),
    NIGHT("לילה", "Night", listOf(0xFF0E1430, 0xFF1A1236, 0xFF0B0F1E), 0xFF8AB4FF, true),
    PHOTO("תמונה שלי", "My photo", listOf(0xFFF4F6FB, 0xFFF4F6FB), 0xFF2F6FED, null);

    val label: String get() = tr(he, en)
    val brush: Brush get() = Brush.linearGradient(colors.map { Color(it) })
}

/** Saved background choice. Compose state, so the app repaints immediately. */
object ThemePrefs {
    private const val PREFS = "settings"
    private const val KEY_BG = "background"
    private const val KEY_VIS = "photo_visibility"
    private var loaded = false

    var background by mutableStateOf(AppBackground.DEFAULT)
        private set

    /** how visible the photo is, 0.1 – 0.9 */
    var photoVisibility by mutableFloatStateOf(0.35f)
        private set

    /** bumps when a new photo is chosen, to reload it */
    var photoVersion by mutableIntStateOf(0)
        private set

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun photoFile(ctx: Context) = File(ctx.applicationContext.filesDir, "background.jpg")

    fun init(ctx: Context) {
        if (loaded) return
        loaded = true
        val p = prefs(ctx)
        background = AppBackground.entries.firstOrNull { it.name == p.getString(KEY_BG, null) } ?: AppBackground.DEFAULT
        if (background == AppBackground.PHOTO && !photoFile(ctx).exists()) background = AppBackground.DEFAULT
        photoVisibility = p.getFloat(KEY_VIS, 0.35f)
    }

    fun setBackground(ctx: Context, bg: AppBackground) {
        prefs(ctx).edit().putString(KEY_BG, bg.name).apply()
        background = bg
    }

    fun setPhotoVisibility(ctx: Context, v: Float) {
        photoVisibility = v.coerceIn(0.1f, 0.9f)
        prefs(ctx).edit().putFloat(KEY_VIS, photoVisibility).apply()
    }

    /** Copies the chosen picture into the app (resized, upright) and selects it. */
    fun importPhoto(ctx: Context, uri: Uri): Boolean = runCatching {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 1400 && bounds.outHeight / (sample * 2) >= 1400) sample *= 2
        var bmp = cr.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("cannot decode")

        // camera photos are often stored sideways with an EXIF rotation flag
        val rotation = runCatching {
            cr.openInputStream(uri)?.use { s ->
                when (ExifInterface(s).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            }
        }.getOrNull() ?: 0f
        if (rotation != 0f) {
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation) }, true)
        }
        photoFile(ctx).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        photoVersion++
        setBackground(ctx, AppBackground.PHOTO)
        true
    }.getOrDefault(false)

    fun loadPhoto(ctx: Context): ImageBitmap? = runCatching {
        val f = photoFile(ctx)
        if (f.exists()) BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap() else null
    }.getOrNull()
}

/** Paints the chosen background behind all screens. */
@Composable
fun AppBackdrop() {
    val ctx = LocalContext.current
    val bg = ThemePrefs.background
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when (bg) {
            AppBackground.DEFAULT -> {}
            AppBackground.PHOTO -> {
                val version = ThemePrefs.photoVersion
                val photo = remember(version) { ThemePrefs.loadPhoto(ctx) }
                if (photo != null) {
                    Image(
                        bitmap = photo,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    // veil in the page color keeps text readable; the slider sets how much photo shows
                    Box(
                        Modifier.fillMaxSize().background(
                            MaterialTheme.colorScheme.background.copy(alpha = 1f - ThemePrefs.photoVisibility)
                        )
                    )
                }
            }
            else -> Box(Modifier.fillMaxSize().background(bg.brush))
        }
    }
}
