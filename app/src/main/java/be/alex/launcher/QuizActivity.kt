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

/**
 * Défi à l'écran : la vidéo est mise en pause, l'enfant répond avec les flèches et OK,
 * puis la vidéo reprend toute seule. Retour ne permet pas de passer ; un parent peut
 * passer le défi depuis le téléphone.
 */
class QuizActivity : Activity() {

    companion object {
        private var current: WeakReference<QuizActivity>? = null
        private val main = Handler(Looper.getMainLooper())

        fun isShowing() = current?.get() != null

        fun show(ctx: Context) {
            main.post {
                if (isShowing()) return@post
                Defis.pending = true
                val app = ctx.applicationContext
                Remote.media(app, KeyEvent.KEYCODE_MEDIA_PAUSE)
                app.startActivity(Intent(app, QuizActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_SINGLE_TOP
                ))
            }
        }

        /** Le parent passe le défi depuis le téléphone. */
        fun skip() {
            Defis.pending = false
            main.post { current?.get()?.finishQuiz(resume = true) }
        }
    }

    private lateinit var questions: List<Defis.Question>
    private var index = 0
    private var fails = 0
    private lateinit var card: LinearLayout
    private lateinit var progress: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = WeakReference(this)
        Look.refresh(this)
        questions = Defis.questions(this)

        val root = FrameLayout(this)
        val wall = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        Wallpapers.apply(this, wall)
        root.addView(wall, match())
        root.addView(View(this).apply { setBackgroundColor(Look.overlay) }, match())

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        col.addView(label("⏸  Petite pause défi !", 18f, bold = true, color = Look.text2))
        progress = label("", 13f, color = Look.text3)
        col.addView(progress, wrap().apply { topMargin = dp(4) })
        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = Look.glass(this@QuizActivity, 28)
            setPadding(dp(48), dp(30), dp(48), dp(34))
            clipChildren = false
            clipToPadding = false
        }
        col.addView(card, LinearLayout.LayoutParams(dp(760), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })
        root.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setContentView(root)
        showQuestion()
    }

    private fun showQuestion() {
        fails = 0
        card.removeAllViews()
        val q = questions[index]
        typing = null
        progress.text = listOfNotNull(
            if (questions.size > 1) "Question ${index + 1} sur ${questions.size}" else null,
            if (q.level.isNotEmpty()) "Niveau ${q.level} ans" else null
        ).joinToString(" · ")
        val big = q.type == "calc" || q.type == "units"
        card.addView(label(q.prompt, if (big) 54f else 28f, bold = true, color = Look.text).apply {
            gravity = Gravity.CENTER
        })
        if (q.hint.isNotBlank()) {
            card.addView(label(q.hint, 16f, color = Look.text2).apply { gravity = Gravity.CENTER }, wrap().apply { topMargin = dp(8) })
        }
        when {
            q.numeric -> numeric(q)
            q.words.isNotEmpty() -> ordering(q)
            else -> choices(q)
        }
    }

    // ---------- Réponse tapée (chiffres de la télécommande ou pavé à l'écran) ----------

    /** Saisie en cours quand la question attend un nombre : les touches 0 à 9 de la télécommande y écrivent. */
    private var typing: ((String) -> Unit)? = null

    private fun numeric(q: Defis.Question) {
        var entered = ""
        val field = label("?", 56f, bold = true, color = Look.text).apply {
            gravity = Gravity.CENTER
            background = Look.glass(this@QuizActivity, 18)
            setPadding(dp(30), dp(4), dp(30), dp(4))
            fontFeatureSettings = "tnum"
        }
        val status = label("", 16f, bold = true, color = Look.text).apply { gravity = Gravity.CENTER }

        fun show() { field.text = entered.ifEmpty { "?" } }
        fun check() {
            if (entered.isEmpty()) return
            if (entered.trimStart('0').ifEmpty { "0" } == q.answer) {
                status.text = Defis.BRAVO.random()
                typing = null
                next()
            } else {
                fails++
                val a = q.answer.toIntOrNull() ?: 0
                status.text = if (fails >= 2) "Indice : c'est entre ${maxOf(0, a - 5)} et ${a + 5}" else Defis.RETRY.random()
                entered = ""
                show()
                shake()
            }
        }
        typing = { k ->
            when (k) {
                "⌫" -> entered = entered.dropLast(1)
                "OK" -> check()
                else -> if (entered.length < 5) entered += k
            }
            if (typing != null) show()
        }

        // Pavé à l'écran, pour ceux qui préfèrent les flèches.
        val pad = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        var first: View? = null
        for (row in listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "OK"))) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
            for (k in row) {
                // « ⌫ » n'existe pas dans toutes les polices des TV : on affiche une flèche.
                val b = button(if (k == "⌫") "←" else k, if (k == "OK") 18f else 22f) { typing?.invoke(k) }
                if (k == "5") first = b
                line.addView(b, LinearLayout.LayoutParams(dp(78), dp(54)).apply { setMargins(dp(5), dp(5), dp(5), dp(5)) })
            }
            pad.addView(line)
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
        row.addView(field, LinearLayout.LayoutParams(dp(220), dp(110)).apply { marginEnd = dp(32) })
        row.addView(pad)
        card.addView(row, wrap().apply { topMargin = dp(18) })
        card.addView(status, wrap().apply { topMargin = dp(10) })
        first?.requestFocus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val t = typing
        if (t != null) {
            when (keyCode) {
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> { t((keyCode - KeyEvent.KEYCODE_0).toString()); return true }
                in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> { t((keyCode - KeyEvent.KEYCODE_NUMPAD_0).toString()); return true }
                KeyEvent.KEYCODE_DEL -> { t("⌫"); return true }
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { t("OK"); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------- Choix parmi 4 réponses (grille 2 × 2) ----------

    private fun choices(q: Defis.Question) {
        val status = label("", 16f, bold = true, color = Look.text).apply { gravity = Gravity.CENTER }
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        var first: View? = null
        val perRow = if (q.type == "good") 1 else 2
        q.choices.chunked(perRow).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
            pair.forEach { c ->
                val b = button(c, if (q.type == "calc" || q.type == "units") 30f else 20f) { v ->
                    if (c == q.answer) {
                        status.text = Defis.BRAVO.random()
                        v.isEnabled = false
                        next()
                    } else {
                        fails++
                        v.alpha = 0.35f
                        v.isFocusable = false
                        status.text = Defis.RETRY.random()
                        shake()
                        focusFirstEnabled(grid)
                    }
                }
                if (first == null) first = b
                row.addView(b, LinearLayout.LayoutParams(if (perRow == 1) dp(620) else dp(300), if (perRow == 1) dp(60) else dp(74))
                    .apply { setMargins(dp(8), dp(if (perRow == 1) 5 else 8), dp(8), dp(if (perRow == 1) 5 else 8)) })
            }
            grid.addView(row)
        }
        card.addView(grid, wrap().apply { topMargin = dp(20) })
        card.addView(status, wrap().apply { topMargin = dp(12) })
        first?.requestFocus()
    }

    // ---------- Phrase à remettre dans l'ordre ----------

    private fun ordering(q: Defis.Question) {
        val target = q.answer.split(' ')
        val picked = mutableListOf<String>()
        val line = label("…", 28f, bold = true, color = Look.text).apply { gravity = Gravity.CENTER }
        val status = label(if (q.joiner.isEmpty()) "Choisis les lettres une par une." else "Choisis les mots un par un.", 15f, color = Look.text2).apply { gravity = Gravity.CENTER }
        // Plusieurs lignes si besoin : les mots passent à la ligne au lieu d'être écrasés.
        val chips = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; clipChildren = false }
        val maxRow = dp(640)
        var rowView: LinearLayout? = null
        var rowWidth = 0
        val buttons = mutableListOf<View>()

        fun refresh() {
            line.text = if (picked.isEmpty()) "…" else picked.joinToString(q.joiner)
        }

        q.words.forEach { w ->
            val b = button(w, 20f) { v ->
                picked += w
                v.visibility = View.INVISIBLE
                v.isFocusable = false
                refresh()
                if (picked.size == target.size) {
                    if (picked == target) {
                        status.text = Defis.BRAVO.random()
                        next()
                    } else {
                        fails++
                        status.text = if (fails >= 2) (if (q.joiner.isEmpty()) "Indice : le mot commence par « ${target.first()} »" else "Indice : la phrase commence par « ${target.first()} »") else Defis.RETRY.random()
                        shake()
                        picked.clear()
                        buttons.forEach { it.visibility = View.VISIBLE; it.isFocusable = true }
                        refresh()
                        buttons.firstOrNull()?.requestFocus()
                    }
                } else {
                    buttons.firstOrNull { it.visibility == View.VISIBLE }?.requestFocus()
                }
            }
            buttons += b
            b.maxLines = 1
            b.setSingleLine(true)
            val cw = b.paint.measureText(w).toInt() + b.paddingLeft + b.paddingRight + dp(12)
            if (rowView == null || rowWidth + cw > maxRow) {
                rowView = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; clipChildren = false }
                chips.addView(rowView, wrap())
                rowWidth = 0
            }
            rowWidth += cw
            rowView!!.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
        }
        val undo = button("←  Effacer", 15f) {
            if (picked.isNotEmpty()) {
                val last = picked.removeAt(picked.size - 1)
                buttons.firstOrNull { (it as TextView).text == last && it.visibility == View.INVISIBLE }?.apply {
                    visibility = View.VISIBLE; isFocusable = true
                }
                refresh()
            }
        }
        card.addView(line, wrap().apply { topMargin = dp(22) })
        card.addView(chips, wrap().apply { topMargin = dp(18) })
        card.addView(undo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { topMargin = dp(10) })
        card.addView(status, wrap().apply { topMargin = dp(12) })
        buttons.firstOrNull()?.requestFocus()
    }

    // ---------- Suite ----------

    private fun next() {
        card.postDelayed({
            index++
            if (index < questions.size) showQuestion() else win()
        }, 900)
    }

    private fun win() {
        card.removeAllViews()
        card.addView(label("🎉", 56f).apply { gravity = Gravity.CENTER })
        card.addView(label("Défi réussi !", 34f, bold = true, color = Look.text).apply { gravity = Gravity.CENTER })
        card.addView(label("Tu peux reprendre ton programme.", 17f, color = Look.text2).apply { gravity = Gravity.CENTER },
            wrap().apply { topMargin = dp(8) })
        progress.text = ""
        card.postDelayed({ finishQuiz(resume = true) }, 2200)
    }

    fun finishQuiz(resume: Boolean) {
        if (isFinishing) return
        Defis.pending = false
        finish()
        overridePendingTransition(0, 0)
        if (resume) main.postDelayed({ Remote.media(applicationContext, KeyEvent.KEYCODE_MEDIA_PLAY) }, 600)
    }

    private fun shake() {
        card.animate().translationX(dpf(14f)).setDuration(60).withEndAction {
            card.animate().translationX(-dpf(10f)).setDuration(70).withEndAction {
                card.animate().translationX(0f).setDuration(90).start()
            }.start()
        }.start()
    }

    private fun focusFirstEnabled(v: View): Boolean {
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) if (focusFirstEnabled(v.getChildAt(i))) return true
            return false
        }
        return if (v.isFocusable && v.alpha > 0.5f) v.requestFocus() else false
    }

    private fun button(text: String, size: Float, onClick: (View) -> Unit): TextView = label(text, size, bold = true).apply {
        gravity = Gravity.CENTER
        setTextColor(Look.pillText())
        background = Look.pillButton(this@QuizActivity, 20)
        setPadding(dp(22), 0, dp(22), 0)
        isClickable = true
        tvFocus(1.07f, 8f)
        setOnClickListener { onClick(it) }
    }

    private fun match() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    private fun wrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Pas d'échappatoire : il faut répondre (ou un parent passe le défi depuis le téléphone).
    }

    override fun onResume() {
        super.onResume()
        Foreground.set(this)
    }

    override fun onPause() {
        super.onPause()
        Foreground.clear(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Écran fermé d'une façon ou d'une autre : plus de défi en attente.
        if (isFinishing) Defis.pending = false
        if (current?.get() === this) current = null
    }
}
