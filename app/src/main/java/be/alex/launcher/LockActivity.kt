package be.alex.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.ref.WeakReference

/** Écran « La télé fait une pause » : il faut le code à 4 chiffres pour continuer. */
class LockActivity : Activity() {

    companion object {
        private var current: WeakReference<LockActivity>? = null
        private val main = Handler(Looper.getMainLooper())

        fun show(ctx: Context) {
            main.post {
                ctx.applicationContext.startActivity(
                    Intent(ctx.applicationContext, LockActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                )
            }
        }

        fun dismiss() {
            main.post { current?.get()?.done() }
        }
    }

    private var entered = ""
    private var fails = 0
    private var blockedUntil = 0L
    private lateinit var dots: List<TextView>
    private lateinit var status: TextView
    private lateinit var firstKey: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Look.refresh(this)

        val root = FrameLayout(this)
        val wall = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        Wallpapers.apply(this, wall)
        root.addView(wall, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(View(this).apply { setBackgroundColor(Look.overlay) },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
        }
        col.addView(label("La télé fait une pause", 30f, bold = true, color = Look.text))
        col.addView(label("Entre le code pour continuer", 15f, color = Look.text2),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

        val dotRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        dots = (0 until 4).map {
            label("○", 28f, color = Look.text).also { d ->
                dotRow.addView(d, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(9); marginEnd = dp(9)
                })
            }
        }
        col.addView(dotRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(22) })

        // Pavé numérique, navigable avec les flèches de la télécommande.
        val pad = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("", "0", "⌫"))
        var first: View? = null
        for (r in rows) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
            for (k in r) {
                val key: View = if (k.isEmpty()) {
                    View(this)
                } else {
                    label(k, 22f, bold = true).apply {
                        gravity = Gravity.CENTER
                        setTextColor(Look.pillText())
                        background = Look.pillButton(this@LockActivity, 27)
                        isClickable = true
                        tvFocus(1.1f, 8f)
                        setOnClickListener { if (k == "⌫") erase() else digit(k) }
                    }
                }
                if (first == null && k.isNotEmpty()) first = key
                line.addView(key, LinearLayout.LayoutParams(dp(54), dp(54)).apply { setMargins(dp(7), dp(7), dp(7), dp(7)) })
            }
            pad.addView(line)
        }
        col.addView(pad, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })

        status = label("", 13f, color = Look.text2)
        col.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })

        root.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setContentView(root)
        firstKey = first ?: root
        firstKey.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        current = WeakReference(this)
        if (!Kids.locked(this)) done()
    }

    override fun onPause() {
        super.onPause()
        if (current?.get() === this) current = null
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Pas d'échappatoire : il faut le code.
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            digit((keyCode - KeyEvent.KEYCODE_0).toString())
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun digit(d: String) {
        val now = System.currentTimeMillis()
        if (now < blockedUntil) {
            status.text = "Trop d'essais. Réessaie dans ${(blockedUntil - now) / 1000 + 1} s."
            return
        }
        if (entered.length >= 4) return
        entered += d
        refreshDots()
        if (entered.length == 4) {
            if (entered == Kids.code(this)) {
                Kids.unlock(this)
                done()
            } else {
                fails++
                entered = ""
                refreshDots()
                if (fails >= 5) {
                    blockedUntil = now + 60_000
                    fails = 0
                    status.text = "Trop d'essais. Attends une minute."
                } else {
                    status.text = "Ce n'est pas le bon code."
                }
                window.decorView.animate().translationX(dpf(12f)).setDuration(60).withEndAction {
                    window.decorView.animate().translationX(0f).setDuration(120).start()
                }.start()
            }
        }
    }

    private fun erase() {
        if (entered.isNotEmpty()) entered = entered.dropLast(1)
        refreshDots()
    }

    private fun refreshDots() {
        dots.forEachIndexed { i, d -> d.text = if (i < entered.length) "●" else "○" }
    }

    private fun done() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }
}
