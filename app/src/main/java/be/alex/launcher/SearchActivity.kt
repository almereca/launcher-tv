package be.alex.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import coil.load
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SearchActivity : Activity() {

    companion object {
        const val EXTRA_QUERY = "phone_query"
        const val EXTRA_SUBMIT = "phone_submit"

        /** Appelé par la page du téléphone : ouvre la recherche (ou la met à jour) avec ce texte. */
        private var current: java.lang.ref.WeakReference<SearchActivity>? = null

        fun fromPhone(ctx: Context, query: String, submit: Boolean) {
            // Écran déjà ouvert : on lui passe le texte directement (pas de rechargement à chaque lettre).
            current?.get()?.let {
                it.phoneText(query, submit)
                return
            }
            val i = Intent(ctx, SearchActivity::class.java)
                .putExtra(EXTRA_QUERY, query)
                .putExtra(EXTRA_SUBMIT, submit)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            ctx.startActivity(i)
        }
    }

    private sealed class Hit {
        abstract val title: String

        class AppHit(override val title: String, val pkg: String) : Hit()
        class Movie(val r: Tmdb.Result, val providers: List<String>) : Hit() {
            override val title: String get() = r.title
        }
        class Video(val r: YouTube.Result) : Hit() {
            override val title: String get() = r.title
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var seq = 0
    private var focusResultsAfterRender = false
    private var settingFromPhone = false

    private lateinit var query: EditText
    private lateinit var status: TextView
    private lateinit var scroll: ScrollView
    private lateinit var results: LinearLayout
    private lateinit var preview: View
    private lateinit var pImage: ImageView
    private lateinit var pTitle: TextView
    private lateinit var pMeta: TextView
    private lateinit var pProviders: TextView
    private lateinit var pOverview: TextView
    private lateinit var pTrailer: TextView
    private var previewHit: Hit? = null
    private var lastRowId = View.NO_ID

    private var installed = listOf<Apps.Installed>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Keys.init(this)
        Look.refresh(this)
        setContentView(R.layout.activity_search)

        query = findViewById(R.id.query)
        status = findViewById(R.id.status)
        scroll = findViewById(R.id.scroll)
        results = findViewById(R.id.results)
        preview = findViewById(R.id.preview)
        pImage = findViewById(R.id.pImage)
        pTitle = findViewById(R.id.pTitle)
        pMeta = findViewById(R.id.pMeta)
        pProviders = findViewById(R.id.pProviders)
        pOverview = findViewById(R.id.pOverview)
        pTrailer = findViewById(R.id.pTrailer)
        pTrailer.tvFocus(1.05f, 0f)
        pTrailer.setOnClickListener { playTrailer() }
        applyLook()

        installed = Apps.launchables(this)

        if (!Tmdb.enabled() && !YouTube.enabled()) {
            status.text = "Clés API absentes : recherche limitée"
        }

        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                if (!settingFromPhone) schedule(s.toString(), 450)
            }
        })
        query.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE) {
                focusResultsAfterRender = true
                schedule(query.text.toString(), 0)
                true
            } else {
                false
            }
        }

        if (intent.hasExtra(EXTRA_QUERY)) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            handlePhone(intent)
        } else {
            query.requestFocus()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasExtra(EXTRA_QUERY)) handlePhone(intent)
    }

    private fun handlePhone(i: Intent) =
        phoneText(i.getStringExtra(EXTRA_QUERY).orEmpty(), i.getBooleanExtra(EXTRA_SUBMIT, false))

    /** Texte tapé sur le téléphone : on l'affiche ici sans ouvrir le clavier de la TV. */
    private fun phoneText(q: String, submit: Boolean) {
        if (q == query.text.toString() && !submit) return
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(query.windowToken, 0)
        settingFromPhone = true
        query.setText(q)
        query.setSelection(q.length)
        settingFromPhone = false
        status.text = "Saisie depuis le téléphone"
        if (submit) focusResultsAfterRender = true
        schedule(q, if (submit) 0 else 350)
    }

    override fun onResume() {
        super.onResume()
        Foreground.set(this)
        current = java.lang.ref.WeakReference(this)
    }

    override fun onPause() {
        super.onPause()
        Foreground.clear(this)
        if (current?.get() === this) current = null
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun applyLook() {
        Wallpapers.apply(this, findViewById(R.id.wallpaper))
        findViewById<View>(R.id.overlay).setBackgroundColor(Look.overlay)
        findViewById<View>(R.id.searchBar).background = Look.glass(this, 25)
        findViewById<ImageView>(R.id.searchIcon).imageTintList = Look.solid(Look.text)
        query.setTextColor(Look.text)
        query.setHintTextColor(Look.text3)
        status.setTextColor(Look.text2)
        preview.background = Look.glass(this, 22)
        pImage.background = Look.cardImage(this)
        pImage.clipToOutline = true
        pTitle.setTextColor(Look.text)
        pMeta.setTextColor(Look.text2)
        pProviders.setTextColor(Look.text)
        pOverview.setTextColor(Look.text2)
        findViewById<TextView>(R.id.hint).setTextColor(Look.text2)
        pTrailer.background = Look.pillButton(this, 18)
        pTrailer.setTextColor(Look.pillText())
    }

    private fun schedule(raw: String, wait: Long) {
        job?.cancel()
        val my = ++seq
        val q = raw.trim()
        job = scope.launch {
            if (q.length < 2) {
                results.removeAllViews()
                preview.visibility = View.INVISIBLE
                return@launch
            }
            delay(wait)
            status.text = "Recherche…"
            val found = withContext(Dispatchers.IO) {
                val apps = installed.filter { it.label.contains(q, ignoreCase = true) }
                    .take(4).map { Hit.AppHit(it.label, it.pkg) }
                val movies = async {
                    try {
                        Tmdb.search(q).take(6).map { r -> async { Hit.Movie(r, Tmdb.providers(r)) } }.awaitAll()
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
                // YouTube coûte du quota : on attend que la saisie se calme.
                val videos = async {
                    try {
                        if (wait == 0L) YouTube.search(q) else { delay(600); YouTube.search(q) }
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
                Found(apps, movies.await(), videos.await())
            }
            if (my != seq) return@launch
            render(q, found)
        }
    }

    private class Found(
        val apps: List<Hit>,
        val movies: List<Hit>,
        val youtube: List<YouTube.Result>
    )

    private fun render(q: String, f: Found) {
        results.removeAllViews()
        val rows = mutableListOf<View>()
        val channels = f.youtube.filter { it.kind == "channel" }.take(3).map { Hit.Video(it) }
        val videos = f.youtube.filter { it.kind == "video" }.take(5).map { Hit.Video(it) }

        if (f.apps.isNotEmpty()) { header("Apps"); f.apps.forEach { rows += row(it) } }
        if (f.movies.isNotEmpty()) { header("Films et séries"); f.movies.forEach { rows += row(it) } }
        if (channels.isNotEmpty()) { header("Chaînes YouTube"); channels.forEach { rows += row(it) } }
        if (videos.isNotEmpty()) { header("Vidéos YouTube"); videos.forEach { rows += row(it) } }
        rows += chips(q)

        val total = f.apps.size + f.movies.size + channels.size + videos.size
        status.text = if (total == 0) "Aucun résultat" else ""
        if (total == 0) preview.visibility = View.INVISIBLE

        val first = rows.firstOrNull()
        if (first != null) {
            query.nextFocusDownId = first.id
            first.nextFocusUpId = R.id.query
            if (focusResultsAfterRender) first.requestFocus()
        }
        focusResultsAfterRender = false
        scroll.scrollTo(0, 0)
    }

    private fun header(text: String) {
        val h = label(text.uppercase(), 11f, bold = true, color = Look.text2).apply {
            letterSpacing = 0.12f
            setPadding(dp(14), dp(14), dp(14), dp(6))
        }
        results.addView(h)
    }

    private fun row(h: Hit): View {
        val right = when (h) {
            is Hit.AppHit -> "App"
            is Hit.Movie -> providerText(h)
            is Hit.Video -> if (h.r.kind == "channel") "Chaîne" else h.r.channel
        }
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Look.result(this@SearchActivity)
            setPadding(dp(14), 0, dp(14), 0)
            isClickable = true
            id = View.generateViewId()
        }
        val t = label(h.title, 19f, bold = true).apply {
            setTextColor(Look.resultText())
            isDuplicateParentStateEnabled = true
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        v.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val r = label(right, 13f).apply {
            setTextColor(Look.resultSub())
            isDuplicateParentStateEnabled = true
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        v.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(12)
        })
        v.tvFocus(1.02f, 6f) { has ->
            if (has) {
                lastRowId = v.id
                showPreview(h)
            }
        }
        v.nextFocusRightId = R.id.pTrailer
        v.setOnClickListener { open(h) }
        v.setOnLongClickListener { longPress(h); true }
        results.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(2) })
        return v
    }

    private fun chips(q: String): List<View> {
        val targets = listOf(Catalog.AUVIO, Catalog.ARTE).mapNotNull { e -> Apps.resolve(e, installed)?.let { e to it } }
        if (targets.isEmpty()) return emptyList()
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(16), dp(14), dp(8))
            clipChildren = false
        }
        line.addView(label("Chercher aussi dans", 14f, color = Look.text2))
        val views = mutableListOf<View>()
        for ((entry, pkg) in targets) {
            val chip = label(entry.label, 14f, bold = true).apply {
                setTextColor(Look.pillText())
                background = Look.pillButton(this@SearchActivity)
                gravity = Gravity.CENTER
                setPadding(dp(16), 0, dp(16), 0)
                isClickable = true
                id = View.generateViewId()
                tvFocus(1.05f, 0f)
                setOnClickListener { Apps.searchIn(this@SearchActivity, pkg, q) }
            }
            line.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply { marginStart = dp(10) })
            views += chip
        }
        results.addView(line)
        return views
    }

    private fun installedProvider(h: Hit.Movie): Pair<AppEntry, String>? =
        h.providers.firstNotNullOfOrNull { name ->
            Catalog.forProvider(name)?.let { e -> Apps.resolve(e, installed)?.let { pkg -> e to pkg } }
        }

    private fun providerText(h: Hit.Movie): String =
        installedProvider(h)?.first?.label ?: h.providers.firstOrNull() ?: "Pas en Belgique"

    private fun showPreview(h: Hit) {
        previewHit = h
        pTrailer.visibility = if (h is Hit.Movie && Tmdb.enabled()) View.VISIBLE else View.GONE
        pTrailer.nextFocusLeftId = lastRowId
        when (h) {
            is Hit.AppHit -> {
                preview.visibility = View.INVISIBLE
                return
            }
            is Hit.Movie -> {
                pImage.load(h.r.backdrop ?: h.r.poster) { crossfade(true) }
                pTitle.text = h.r.title
                pMeta.text = listOfNotNull(if (h.r.type == "tv") "Série" else "Film", h.r.year).joinToString(" · ")
                pProviders.text = if (h.providers.isEmpty()) "Aucune plateforme trouvée en Belgique"
                else "Sur " + h.providers.take(3).joinToString(", ")
                pOverview.text = h.r.overview + "\n\nAppui long : épingler · Droite : bande-annonce"
            }
            is Hit.Video -> {
                pImage.load(h.r.thumb) { crossfade(true) }
                pTitle.text = h.r.title
                pMeta.text = if (h.r.kind == "channel") "Chaîne YouTube" else "Vidéo YouTube"
                pProviders.text = h.r.channel
                pOverview.text = if (h.r.kind == "video") "Appui long : épingler la vidéo ou sa chaîne" else "Appui long : épingler la chaîne"
            }
        }
        preview.visibility = View.VISIBLE
    }

    private fun youtubePkg(): String? = Apps.resolve(Catalog.YOUTUBE, installed)

    /** Bande-annonce du film ou de la série affiché dans l'aperçu, ouverte dans YouTube. */
    private fun playTrailer() {
        val h = previewHit as? Hit.Movie ?: return
        pTrailer.text = "Recherche…"
        scope.launch {
            val key = withContext(Dispatchers.IO) { Tmdb.trailer(h.r, Prefs.trailerVO(this@SearchActivity)) }
            pTrailer.text = "▶  Bande-annonce"
            if (key == null) toast("Pas de bande-annonce trouvée.")
            else Apps.openUri(this@SearchActivity, "https://www.youtube.com/watch?v=$key", youtubePkg())
        }
    }

    private fun open(h: Hit) {
        when (h) {
            is Hit.AppHit -> Apps.launch(this, h.pkg)
            is Hit.Movie -> {
                val target = installedProvider(h)
                when {
                    target != null -> Apps.searchIn(this, target.second, h.r.title)
                    h.providers.isEmpty() -> toast("Pas trouvé sur une plateforme en Belgique.")
                    else -> toast("Disponible sur ${h.providers.first()}, mais l'app n'est pas installée.")
                }
            }
            is Hit.Video -> Apps.openUri(this, YouTube.uri(h.r), youtubePkg())
        }
    }

    private fun longPress(h: Hit) {
        if (h is Hit.Video && h.r.kind == "video" && h.r.channelId.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(h.r.title)
                .setItems(arrayOf("Épingler la vidéo", "Épingler la chaîne « ${h.r.channel} »")) { _, which ->
                    if (which == 0) pin(h) else pinYoutubeChannel(h.r)
                }
                .show()
        } else {
            pin(h)
        }
    }

    private fun pinYoutubeChannel(video: YouTube.Result) {
        scope.launch {
            val ch = withContext(Dispatchers.IO) {
                try {
                    YouTube.channel(id = video.channelId)
                } catch (e: Exception) {
                    null
                }
            } ?: YouTube.Result("channel", video.channelId, video.channel, video.channel, video.channelId, null)
            pin(Hit.Video(ch))
        }
    }

    private fun pin(h: Hit) {
        val p = when (h) {
            is Hit.AppHit -> Pin(Pins.newId(), h.title, "App", null, null, h.pkg, null)
            is Hit.Movie -> {
                val target = installedProvider(h)
                Pin(Pins.newId(), h.r.title, target?.first?.label ?: (if (h.r.type == "tv") "Série" else "Film"),
                    h.r.backdrop ?: h.r.poster, null, target?.second, h.r.title)
            }
            is Hit.Video -> Pin(
                Pins.newId(), h.r.title,
                if (h.r.kind == "channel") "Chaîne YouTube" else "YouTube · ${h.r.channel}",
                h.r.thumb, YouTube.uri(h.r), youtubePkg(), null
            )
        }
        Pins.add(this, p)
        toast("« ${p.title} » ajouté à À suivre")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
