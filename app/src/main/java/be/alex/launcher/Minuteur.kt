package be.alex.launcher

import android.content.Context
import android.graphics.PixelFormat
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/**
 * Minuteur lancé depuis le téléphone : un petit compte à rebours s'affiche en haut au centre,
 * par-dessus n'importe quelle app, puis une sonnerie et un message à la fin.
 * L'affichage par-dessus les apps est autorisé par le script d'installation ; sans cette
 * autorisation, la TV prévient seulement par un message à la fin.
 */
object Minuteur {
    private val main = Handler(Looper.getMainLooper())
    private var endsAt = 0L
    private var label = ""
    private var pill: View? = null
    private var pillText: TextView? = null
    private var ring: android.media.Ringtone? = null

    fun remaining(): Long = (endsAt - System.currentTimeMillis()).coerceAtLeast(0)

    fun state(): JSONObject = JSONObject().put("running", endsAt > System.currentTimeMillis())
        .put("endsAt", if (endsAt > 0) endsAt else JSONObject.NULL).put("label", label)

    fun start(ctx: Context, seconds: Int, name: String) {
        val app = ctx.applicationContext
        main.post {
            stopRinging()
            hideBig(app)
            // Repart d'une pastille neuve (elle a pu rester orange après le minuteur précédent).
            hide(app)
            endsAt = System.currentTimeMillis() + seconds.coerceIn(5, 6 * 3600) * 1000L
            label = name.take(30)
            show(app)
            main.removeCallbacks(tick)
            main.post(tick)
        }
    }

    fun cancel(ctx: Context) {
        main.post {
            endsAt = 0
            main.removeCallbacks(tick)
            stopRinging()
            hide(ctx.applicationContext)
            hideBig(ctx.applicationContext)
        }
    }

    private lateinit var appCtx: Context

    private val tick = object : Runnable {
        override fun run() {
            val left = remaining()
            if (endsAt == 0L) return
            if (left <= 0) {
                finish()
                return
            }
            pillText?.text = format(left)
            main.postDelayed(this, 1000L - (System.currentTimeMillis() % 1000L))
        }
    }

    private fun finish() {
        endsAt = 0
        val ctx = appCtx
        val msg = if (label.isBlank()) "C'est l'heure !" else "$label : c'est l'heure !"
        // La petite pastille du haut passe en orange avec le message, sonne quelques secondes, puis s'en va.
        show(ctx)
        val box = pill
        pillText?.apply {
            text = msg
            setTextColor(0xFF1A1206.toInt())
        }
        box?.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = ctx.dpf(16f)
            setColor(0xFFF2B46A.toInt())
        }
        box?.let { b ->
            // Petit rebond pour attirer l'œil, sans envahir l'écran.
            b.animate().scaleX(1.15f).scaleY(1.15f).setDuration(220).withEndAction {
                b.animate().scaleX(1f).scaleY(1f).setDuration(260).start()
            }.start()
        }
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ring = RingtoneManager.getRingtone(ctx, uri)?.also { it.play() }
        } catch (e: Exception) {
        }
        if (box == null) Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        main.postDelayed({ if (endsAt == 0L) { stopRinging(); hide(ctx) } }, 6_000)
    }

    private fun stopRinging() {
        try {
            ring?.stop()
        } catch (e: Exception) {
        }
        ring = null
    }

    private fun format(ms: Long): String {
        val s = (ms + 999) / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        val t = if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
        return t
    }

    private fun show(ctx: Context) {
        appCtx = ctx
        if (pill != null) return
        if (!Settings.canDrawOverlays(ctx)) {
            Toast.makeText(ctx, "Minuteur lancé", Toast.LENGTH_SHORT).show()
            return
        }
        Look.refresh(ctx)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Look.glass(ctx, 16)
            setPadding(ctx.dp(12), ctx.dp(5), ctx.dp(14), ctx.dp(5))
            elevation = ctx.dpf(6f)
            alpha = 0.92f
        }
        // Version miniature : une petite icône d'horloge et le temps restant, rien d'autre.
        box.addView(ctx.label("⏱", 13f, color = Look.text2))
        val t = ctx.label(format(remaining()), 15f, bold = true, color = Look.text).apply {
            setPadding(ctx.dp(6), 0, 0, 0)
            fontFeatureSettings = "tnum"
        }
        box.addView(t)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            // En haut au centre : l'horloge de l'accueil occupe déjà le coin droit.
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = ctx.dp(22)
        }
        try {
            (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(box, lp)
            pill = box
            pillText = t
        } catch (e: Exception) {
            pill = null
        }
    }

    private var big: View? = null

    /** Grand message centré quand le minuteur arrive à zéro. */
    private fun showBig(ctx: Context) {
        if (!Settings.canDrawOverlays(ctx)) return
        hideBig(ctx)
        Look.refresh(ctx)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = Look.glass(ctx, 36)
            setPadding(ctx.dp(64), ctx.dp(36), ctx.dp(64), ctx.dp(40))
            elevation = ctx.dpf(16f)
        }
        box.addView(ctx.label("⏱", 40f, color = Look.text).apply { gravity = Gravity.CENTER })
        box.addView(ctx.label("C'est l'heure", 56f, bold = true, color = Look.text).apply {
            gravity = Gravity.CENTER
            setPadding(0, ctx.dp(6), 0, 0)
        })
        if (label.isNotBlank()) {
            box.addView(ctx.label(label, 26f, color = Look.text2).apply {
                gravity = Gravity.CENTER
                setPadding(0, ctx.dp(6), 0, 0)
            })
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }
        try {
            box.alpha = 0f
            box.scaleX = 0.9f
            box.scaleY = 0.9f
            (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(box, lp)
            box.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).start()
            big = box
        } catch (e: Exception) {
            big = null
        }
    }

    private fun hideBig(ctx: Context) {
        val v = big ?: return
        try {
            (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (e: Exception) {
        }
        big = null
    }

    private fun hide(ctx: Context) {
        val v = pill ?: return
        try {
            (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (e: Exception) {
        }
        pill = null
        pillText = null
    }
}
