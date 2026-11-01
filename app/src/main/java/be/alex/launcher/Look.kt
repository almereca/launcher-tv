package be.alex.launcher

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.widget.ImageView
import coil.load
import coil.request.CachePolicy
import java.io.File
import java.util.Calendar

/** Réglages persistants du launcher. */
object Prefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    fun wallpaper(ctx: Context): String = p(ctx).getString("wallpaper", "montagnes") ?: "montagnes"
    fun setWallpaper(ctx: Context, id: String) = p(ctx).edit().putString("wallpaper", id).apply()

    /** "auto", "clair" ou "sombre". */
    fun theme(ctx: Context): String = p(ctx).getString("theme", "auto") ?: "auto"
    fun setTheme(ctx: Context, mode: String) = p(ctx).edit().putString("theme", mode).apply()

    /** Lever et coucher du soleil à Mons, en minutes depuis minuit. */
    fun setSun(ctx: Context, sunriseMin: Int, sunsetMin: Int) =
        p(ctx).edit().putInt("sunrise", sunriseMin).putInt("sunset", sunsetMin).apply()

    /** Voile appliqué sur une photo perso pour la lisibilité, de 0 à 70 %. */
    fun dim(ctx: Context): Int = p(ctx).getInt("dim", 25)
    fun setDim(ctx: Context, percent: Int) = p(ctx).edit().putInt("dim", percent.coerceIn(0, 70)).apply()

    /** Incrémenté à chaque nouvelle photo, pour forcer le rechargement de l'image. */
    fun photoVersion(ctx: Context): Int = p(ctx).getInt("photoVersion", 0)
    fun bumpPhotoVersion(ctx: Context) = p(ctx).edit().putInt("photoVersion", photoVersion(ctx) + 1).apply()

    /** Apps de la colonne de gauche, dans l'ordre ; null tant que rien n'a été réglé. */
    fun menuApps(ctx: Context): List<String>? =
        p(ctx).getString("menuApps", null)?.split(',')?.filter { it.isNotBlank() }

    fun setMenuApps(ctx: Context, pkgs: List<String>) =
        p(ctx).edit().putString("menuApps", pkgs.distinct().joinToString(",")).apply()

    /** Raccourcis d'apps affichés sur la télécommande du téléphone, dans l'ordre. */
    fun remoteApps(ctx: Context): List<String> =
        p(ctx).getString("remoteApps", "")!!.split(',').filter { it.isNotBlank() }

    fun setRemoteApps(ctx: Context, pkgs: List<String>) =
        p(ctx).edit().putString("remoteApps", pkgs.distinct().take(12).joinToString(",")).apply()

    /** Bandes-annonces en version originale (sinon en français d'abord). */
    fun trailerVO(ctx: Context): Boolean = p(ctx).getBoolean("trailerVO", true)
    fun setTrailerVO(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("trailerVO", on).apply()

    fun sunrise(ctx: Context): Int = p(ctx).getInt("sunrise", 7 * 60)
    fun sunset(ctx: Context): Int = p(ctx).getInt("sunset", 19 * 60)

    fun isDark(ctx: Context): Boolean = when (theme(ctx)) {
        "sombre" -> true
        "clair" -> false
        else -> {
            val c = Calendar.getInstance()
            val now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
            val rise = p(ctx).getInt("sunrise", 7 * 60)
            val set = p(ctx).getInt("sunset", 19 * 60)
            now < rise || now >= set
        }
    }
}

object Wallpapers {
    data class Wp(val id: String, val name: String, val night: Int, val day: Int)

    val all = listOf(
        Wp("montagnes", "Montagnes", R.drawable.wp_montagnes_nuit, R.drawable.wp_montagnes_jour),
        Wp("dunes", "Dunes", R.drawable.wp_dunes_nuit, R.drawable.wp_dunes_jour),
        Wp("ocean", "Océan", R.drawable.wp_ocean_nuit, R.drawable.wp_ocean_jour),
        Wp("collines", "Collines", R.drawable.wp_collines_nuit, R.drawable.wp_collines_jour),
        Wp("aurore", "Aurore", R.drawable.wp_aurore_nuit, R.drawable.wp_aurore_jour)
    )

    const val PHOTO = "photo"

    fun get(id: String): Wp = all.firstOrNull { it.id == id } ?: all[0]

    /** Image intégrée correspondant au choix et au thème (pour une photo : le fond par défaut). */
    fun current(ctx: Context): Int = get(Prefs.wallpaper(ctx)).let { if (Look.dark) it.night else it.day }

    fun res(id: String, dark: Boolean): Int = get(id).let { if (dark) it.night else it.day }

    fun photoFile(ctx: Context): File = File(ctx.filesDir, "wallpaper.jpg")

    fun hasPhoto(ctx: Context): Boolean = photoFile(ctx).let { it.exists() && it.length() > 0 }

    fun usingPhoto(ctx: Context): Boolean = Prefs.wallpaper(ctx) == PHOTO && hasPhoto(ctx)

    /** Affiche le fond choisi dans cette image : fond intégré ou photo du téléphone avec son voile. */
    fun apply(ctx: Context, iv: ImageView) {
        if (Demo.usingPhoto(ctx)) {
            // Captures d'écran : photo Unsplash neutre à la place de la photo perso.
            iv.load(Demo.photo(ctx)) {
                memoryCacheKey("demo-" + Demo.credit)
                diskCachePolicy(CachePolicy.DISABLED)
            }
            val veil = if (Look.dark) Color.argb(64, 0, 0, 0) else Color.argb(64, 255, 255, 255)
            iv.setColorFilter(veil, PorterDuff.Mode.SRC_ATOP)
            return
        }
        if (usingPhoto(ctx)) {
            val f = photoFile(ctx)
            iv.load(f) {
                memoryCacheKey("wallpaper-" + Prefs.photoVersion(ctx))
                diskCachePolicy(CachePolicy.DISABLED)
            }
            val a = Prefs.dim(ctx) * 255 / 100
            val veil = if (Look.dark) Color.argb(a, 0, 0, 0) else Color.argb(a, 255, 255, 255)
            iv.setColorFilter(veil, PorterDuff.Mode.SRC_ATOP)
        } else {
            iv.clearColorFilter()
            iv.setImageResource(current(ctx))
        }
    }
}

/** Couleurs et surfaces du thème clair ou sombre. */
object Look {
    var dark = true
        private set

    /** Recalcule le thème ; renvoie true s'il a changé. */
    fun refresh(ctx: Context): Boolean {
        val d = Prefs.isDark(ctx)
        val changed = d != dark
        dark = d
        return changed
    }

    private fun c(v: Long): Int = v.toInt()

    val text: Int get() = if (dark) Color.WHITE else c(0xFF15171A)
    val text2: Int get() = if (dark) c(0xBFFFFFFF) else c(0xB315171A)
    val text3: Int get() = if (dark) c(0x99FFFFFF) else c(0x8C15171A)
    val focusFill: Int get() = if (dark) Color.WHITE else c(0xFF15171A)
    val focusText: Int get() = if (dark) c(0xFF0A0A0A) else Color.WHITE
    private val glassFill: Int get() = if (dark) c(0x1FFFFFFF) else c(0x99FFFFFF)
    private val glassStroke: Int get() = if (dark) c(0x29FFFFFF) else c(0x1A000000)
    private val menuFocusFill: Int get() = if (dark) c(0x2EFFFFFF) else c(0x8CFFFFFF)
    private val menuFocusStroke: Int get() = if (dark) c(0x3DFFFFFF) else c(0x26000000)
    private val placeholder: Int get() = if (dark) c(0x26FFFFFF) else c(0x66FFFFFF)
    val overlay: Int get() = if (dark) c(0xB3040810) else c(0xA6F4F1EC)
    val divider: Int get() = if (dark) c(0x40FFFFFF) else c(0x33000000)
    val clockShadow: Float get() = if (dark) 12f else 0f

    private fun Context.shape(fill: Int, radiusDp: Int, stroke: Int = 0, strokeDp: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpf(radiusDp.toFloat())
            setColor(fill)
            if (stroke != 0) setStroke(dp(strokeDp), stroke)
        }

    private fun focusable(focused: Drawable, normal: Drawable): StateListDrawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(), normal)
        }

    fun glass(ctx: Context, radiusDp: Int = 17): Drawable = ctx.shape(glassFill, radiusDp, glassStroke)
    fun pillButton(ctx: Context, radiusDp: Int = 17): Drawable = focusable(ctx.shape(focusFill, radiusDp), glass(ctx, radiusDp))
    fun menuItem(ctx: Context): Drawable = focusable(ctx.shape(menuFocusFill, 14, menuFocusStroke), ColorDrawable(Color.TRANSPARENT))
    fun result(ctx: Context): Drawable = focusable(ctx.shape(focusFill, 12), ColorDrawable(Color.TRANSPARENT))
    fun tile(ctx: Context): Drawable = focusable(ctx.shape(focusFill, 14), glass(ctx, 14))
    fun cardImage(ctx: Context): Drawable = ctx.shape(placeholder, 12)
    fun cardFocus(ctx: Context): Drawable =
        focusable(ctx.shape(Color.TRANSPARENT, 12, focusFill, 3), ColorDrawable(Color.TRANSPARENT))

    /** Contour permanent pour l'élément sélectionné dans les réglages. */
    fun selected(ctx: Context, radiusDp: Int = 12): Drawable =
        focusable(ctx.shape(Color.TRANSPARENT, radiusDp, focusFill, 3), ctx.shape(Color.TRANSPARENT, radiusDp, text2, 2))

    /** Pastille d'interrupteur : pleine si activé, vide sinon (et inversée quand la ligne est sélectionnée). */
    fun toggleDot(ctx: Context, on: Boolean): Drawable {
        fun oval(fill: Int, stroke: Int) = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            setStroke(ctx.dp(2), stroke)
        }
        return focusable(
            if (on) oval(focusText, focusText) else oval(Color.TRANSPARENT, focusText),
            if (on) oval(text, text) else oval(Color.TRANSPARENT, text2)
        )
    }

    fun pillText(): ColorStateList = focusColors(focusText, text)
    fun menuText(): ColorStateList = focusColors(text, text2)
    fun resultText(): ColorStateList = focusColors(focusText, text)
    fun resultSub(): ColorStateList = focusColors(if (dark) c(0xFF3D3D3D) else c(0xD9FFFFFF), text2)
    fun solid(color: Int): ColorStateList = ColorStateList.valueOf(color)

    /** Voile sur la gauche pour que la liste reste lisible sur n'importe quel fond. */
    fun scrim(): Drawable = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        if (dark) intArrayOf(c(0xA6000000), c(0x4D000000), Color.TRANSPARENT)
        else intArrayOf(c(0xB3FFFFFF), c(0x4DFFFFFF), c(0x00FFFFFF))
    )
}
