package be.alex.launcher

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var searchBtn: View
    private lateinit var nextBtn: View
    private lateinit var appList: LinearLayout
    private lateinit var rowScroll: HorizontalScrollView
    private lateinit var row: LinearLayout
    private lateinit var weather: View
    private lateinit var nowRow: View

    private var installed = listOf<Apps.Installed>()
    private var weatherAt = 0L

    /** Thème avec lequel cet écran a été dessiné (le thème global peut changer ailleurs, ex. dans les réglages). */
    private var builtDark = true

    private fun themeChanged(): Boolean {
        Look.refresh(this)
        return Look.dark != builtDark
    }
    private var rowShown = true
    private var weatherLoaded = false

    /** Vérifie régulièrement s'il faut passer en thème jour ou nuit, et rafraîchit la météo. */
    private val tick = object : Runnable {
        override fun run() {
            if (themeChanged()) {
                recreate()
                return
            }
            if (System.currentTimeMillis() - weatherAt > 15 * 60 * 1000L) loadWeather()
            loadAgenda()
            handler.postDelayed(this, 5 * 60 * 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Keys.init(this)
        Logos.cleanup(this)
        Look.refresh(this)
        builtDark = Look.dark
        setContentView(R.layout.activity_main)

        searchBtn = findViewById(R.id.searchBtn)
        nextBtn = findViewById(R.id.nextBtn)
        appList = findViewById(R.id.appList)
        rowScroll = findViewById(R.id.rowScroll)
        row = findViewById(R.id.row)
        weather = findViewById(R.id.weather)
        nowRow = findViewById(R.id.nowRow)

        applyLook()

        searchBtn.pivotX = 0f
        searchBtn.tvFocus(1.05f, 0f)
        nextBtn.pivotX = 0f
        nextBtn.tvFocus(1.04f, 0f)

        searchBtn.setOnClickListener { openSearch() }
        nextBtn.setOnClickListener { row.getChildAt(0)?.requestFocus() }

        findViewById<View>(R.id.allAppsBtn).apply {
            tvFocus(1.05f, 0f)
            setOnClickListener { startActivity(Intent(this@MainActivity, AllAppsActivity::class.java)) }
        }
        findViewById<View>(R.id.settingsBtn).apply {
            tvFocus(1.05f, 0f)
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
            // Appui long : directement les réglages d'Android.
            setOnLongClickListener {
                if (!Apps.start(this@MainActivity, Intent(android.provider.Settings.ACTION_SETTINGS))) {
                    Toast.makeText(this@MainActivity, "Impossible d'ouvrir les réglages de la TV", Toast.LENGTH_SHORT).show()
                }
                true
            }
        }
        findViewById<View>(R.id.phoneBtn).apply {
            tvFocus(1.05f, 0f)
            setOnClickListener { showQr() }
        }

        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, now -> onFocusMoved(now) }

        Pins.onChange = { buildRow() }
        PinServer.ensure(this)
        PinServer.onPaired = {
            Toast.makeText(this, "Téléphone connecté au launcher", Toast.LENGTH_SHORT).show()
        }
        PinServer.onWallpaper = {
            if (themeChanged()) recreate() else {
                Wallpapers.apply(this, findViewById(R.id.wallpaper))
                applyWidgets()
            }
        }
        PinServer.onApps = {
            buildApps()
            applyWidgets()
        }
        PinServer.onHome = {
            applyWidgets()
            loadAgenda()
        }

        if (checkSelfPermission(WatchNext.PERM) != PackageManager.PERMISSION_GRANTED) {
            try {
                requestPermissions(arrayOf(WatchNext.PERM), 1)
            } catch (e: Exception) {
            }
        }
        applyWidgets()
        focusStart()
        handleDemo(intent)
    }

    /** Premier élément sélectionné : À suivre, sinon la première app, sinon Rechercher. */
    private fun focusStart() {
        when {
            appList.childCount > 0 -> appList.getChildAt(0).requestFocus()
            else -> searchBtn.requestFocus()
        }
    }

    /** Affiche ou masque chaque élément de l'accueil selon les réglages du téléphone. */
    private fun applyWidgets() {
        fun vis(on: Boolean) = if (on) View.VISIBLE else View.GONE
        findViewById<View>(R.id.clock).visibility = vis(HomeConfig.shown(this, "clock"))
        val showWeather = HomeConfig.shown(this, "weather")
        nowRow.visibility = if (showWeather && weatherLoaded) View.VISIBLE else if (showWeather) View.INVISIBLE else View.GONE
        weather.visibility = if (showWeather && weatherLoaded) View.VISIBLE else if (showWeather) View.INVISIBLE else View.GONE
        if (!HomeConfig.shown(this, "agenda")) findViewById<View>(R.id.agendaCard).visibility = View.GONE
        val showRow = nextInMenu
        nextBtn.visibility = vis(showRow)
        if (!showRow) {
            rowScroll.visibility = View.GONE
            rowShown = false
        } else if (rowScroll.visibility == View.GONE) {
            rowScroll.visibility = View.INVISIBLE
        }
        searchBtn.visibility = vis(HomeConfig.shown(this, "search"))
        findViewById<View>(R.id.allAppsBtn).visibility = vis(HomeConfig.shown(this, "allapps"))
        findViewById<View>(R.id.settingsBtn).visibility = vis(HomeConfig.shown(this, "settings"))
        findViewById<View>(R.id.phoneBtn).visibility = vis(HomeConfig.shown(this, "phone"))
        val demoPhoto = Demo.usingPhoto(this)
        val credit = if (demoPhoto) Demo.credit else Unsplash.credit(this)
        findViewById<TextView>(R.id.credit).apply {
            text = credit
            // Repère lu par captures.sh pour vérifier que la photo de démo est bien affichée.
            contentDescription = if (demoPhoto) "demo-photo" else null
            visibility = vis(credit.isNotBlank() && (demoPhoto ||
                (HomeConfig.shown(this@MainActivity, "credit") && Wallpapers.usingPhoto(this@MainActivity))))
        }
        if (currentFocus?.visibility != View.VISIBLE) focusStart()
    }

    override fun onResume() {
        super.onResume()
        Foreground.set(this)
        if (Kids.locked(this)) {
            LockActivity.show(this)
            return
        }
        if (themeChanged()) {
            recreate()
            return
        }
        // Le fond a pu changer dans les réglages.
        Wallpapers.apply(this, findViewById(R.id.wallpaper))
        PinServer.ensure(this)
        installed = Apps.launchables(this)
        buildApps()
        buildRow()
        applyWidgets()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        Foreground.clear(this)
        handler.removeCallbacks(tick)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        rowScroll.scrollTo(0, 0)
        focusStart()
        handleDemo(intent)
    }

    /**
     * Commandes du script de captures d'écran (captures.sh), envoyées par adb :
     * demo (faux agenda), demo_focus, demo_open, demo_timer. Sans effet si absentes.
     */
    private fun handleDemo(i: Intent?) {
        if (i == null) return
        if (i.hasExtra("demo")) {
            Demo.on = i.getBooleanExtra("demo", false)
            if (!Demo.on) Demo.credit = ""
            loadAgenda()
            Wallpapers.apply(this, findViewById(R.id.wallpaper))
            applyWidgets()
        }
        i.getStringExtra("demo_unsplash")?.let { q ->
            Demo.prepare(this, q) {
                Wallpapers.apply(this, findViewById(R.id.wallpaper))
                applyWidgets()
            }
        }
        when (i.getStringExtra("demo_focus")) {
            "next" -> handler.postDelayed({ if (nextInMenu) nextBtn.requestFocus() }, 700)
            "card" -> handler.postDelayed({ row.getChildAt(0)?.requestFocus() }, 900)
            "app" -> handler.postDelayed({
                (0 until appList.childCount).map { appList.getChildAt(it) }.firstOrNull { it !== nextBtn }?.requestFocus()
            }, 700)
        }
        val t = i.getIntExtra("demo_timer", 0)
        if (t > 0) Minuteur.start(this, t, i.getStringExtra("demo_timer_label").orEmpty())
        if (t < 0) Minuteur.cancel(this)
        if (i.getBooleanExtra("demo_defi", false)) QuizActivity.show(this)
        if (i.getBooleanExtra("demo_defi_skip", false)) QuizActivity.skip()
        val open = i.getStringExtra("demo_open").orEmpty()
        when {
            open.startsWith("search:") -> SearchActivity.fromPhone(this, open.removePrefix("search:"), true)
            open.startsWith("settings:") -> startActivity(Intent(this, SettingsActivity::class.java).putExtra("cat", open.removePrefix("settings:")))
            open == "allapps" -> startActivity(Intent(this, AllAppsActivity::class.java))
        }
        listOf("demo", "demo_focus", "demo_timer", "demo_timer_label", "demo_open", "demo_defi", "demo_defi_skip", "demo_unsplash").forEach { i.removeExtra(it) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        buildRow()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        rowScroll.smoothScrollTo(0, 0)
        focusStart()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_SEARCH) {
            openSearch()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        Pins.onChange = null
        PinServer.onPaired = null
        PinServer.onWallpaper = null
        PinServer.onApps = null
        PinServer.onHome = null
        handler.removeCallbacks(tick)
        scope.cancel()
    }

    private fun openSearch() {
        startActivity(Intent(this, SearchActivity::class.java))
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    // ---------- Thème ----------

    private fun applyLook() {
        Wallpapers.apply(this, findViewById(R.id.wallpaper))
        findViewById<View>(R.id.scrim).background = Look.scrim()

        searchBtn.background = Look.pillButton(this)
        findViewById<ImageView>(R.id.searchIcon).imageTintList = Look.pillText()
        findViewById<TextView>(R.id.searchText).setTextColor(Look.pillText())

        nextBtn.background = Look.menuItem(this)
        findViewById<ImageView>(R.id.nextIcon).imageTintList = Look.menuText()
        findViewById<TextView>(R.id.nextText).setTextColor(Look.menuText())

        findViewById<View>(R.id.allAppsBtn).background = Look.pillButton(this)
        findViewById<ImageView>(R.id.allAppsIcon).imageTintList = Look.pillText()
        findViewById<TextView>(R.id.allAppsText).setTextColor(Look.pillText())
        findViewById<View>(R.id.settingsBtn).background = Look.pillButton(this)
        findViewById<ImageView>(R.id.settingsIcon).imageTintList = Look.pillText()
        findViewById<TextView>(R.id.settingsText).setTextColor(Look.pillText())

        findViewById<TextClock>(R.id.clock).apply {
            setTextColor(Look.text)
            setShadowLayer(Look.clockShadow, 0f, 0f, 0x59000000)
        }
        findViewById<TextView>(R.id.nowLabel).setTextColor(Look.text2)
        findViewById<ImageView>(R.id.wNow).imageTintList = Look.solid(Look.text)
        findViewById<TextView>(R.id.tNow).setTextColor(Look.text)
        weather.background = Look.glass(this, 15)
        findViewById<TextView>(R.id.lToday).setTextColor(Look.text2)
        findViewById<TextView>(R.id.lTomorrow).setTextColor(Look.text2)
        findViewById<ImageView>(R.id.wToday).imageTintList = Look.solid(Look.text)
        findViewById<ImageView>(R.id.wTomorrow).imageTintList = Look.solid(Look.text)
        findViewById<TextView>(R.id.tToday).setTextColor(Look.text)
        findViewById<TextView>(R.id.tTomorrow).setTextColor(Look.text)
        findViewById<View>(R.id.wDivider).setBackgroundColor(Look.divider)
        findViewById<TextView>(R.id.phoneBtn).apply {
            background = Look.pillButton(this@MainActivity)
            setTextColor(Look.pillText())
        }
        findViewById<TextView>(R.id.credit).setTextColor(Look.text3)
        findViewById<View>(R.id.agendaCard).background = Look.glass(this, 16)
    }

    // ---------- Colonne des apps ----------

    /** « À suivre » fait partie de la liste des apps (affiché, renommé, déplacé depuis le téléphone). */
    private var nextInMenu = true
    private var focusedOnce = false

    private fun buildApps() {
        appList.removeAllViews()
        (nextBtn.parent as? ViewGroup)?.removeView(nextBtn)
        var previous: View = searchBtn
        val menu = Apps.menu(this, installed)
        nextInMenu = menu.any { it.pkg == Apps.NEXT }
        for (app in menu) {
            val pkg = app.pkg
            if (pkg == Apps.NEXT) {
                nextBtn.findViewById<TextView>(R.id.nextText).apply {
                    text = app.label
                    textSize = 24f
                }
                nextBtn.setPadding(dp(14), dp(7), dp(14), dp(7))
                nextBtn.visibility = View.VISIBLE
                appList.addView(nextBtn, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(1) })
                previous.nextFocusDownId = nextBtn.id
                nextBtn.nextFocusUpId = previous.id
                previous = nextBtn
                continue
            }
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Look.menuItem(this@MainActivity)
                setPadding(dp(14), dp(7), dp(14), dp(7))
                id = View.generateViewId()
                isClickable = true
                contentDescription = app.label
                pivotX = 0f
                tvFocus(1.04f, 0f)
                setOnClickListener { Apps.launch(this@MainActivity, pkg) }
            }
            item.addView(label(app.label, 24f, bold = true).apply {
                setTextColor(Look.menuText())
                isDuplicateParentStateEnabled = true
            })
            appList.addView(item, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(1) })
            previous.nextFocusDownId = item.id
            item.nextFocusUpId = previous.id
            previous = item
        }
        if (!focusedOnce) {
            focusedOnce = true
            focusStart()
        }
    }

    // ---------- Rangée À suivre ----------

    private fun buildRow() {
        scope.launch {
            val pins = Pins.all(this@MainActivity)
            val next = withContext(Dispatchers.IO) { WatchNext.load(this@MainActivity) }
            val filtered = next.filter { n -> pins.none { it.title.equals(n.title, ignoreCase = true) } }

            val hadFocus = row.hasFocus()
            row.removeAllViews()
            val cards = mutableListOf<View>()

            for (p in pins) {
                cards += addCard(CardSpec(p.title, p.subtitle, p.image, true, null),
                    onClick = { Pins.open(this@MainActivity, p) },
                    onLong = { pinMenu(p) })
            }
            if (pins.isNotEmpty() && filtered.isNotEmpty()) row.addView(divider())
            for (n in filtered) {
                cards += addCard(CardSpec(n.title, n.subtitle, n.image, false, n.progress),
                    onClick = { WatchNext.open(this@MainActivity, n) },
                    onLong = { nextMenu(n) })
            }
            cards += addPlusCard()

            cards.forEachIndexed { i, c ->
                c.nextFocusUpId = c.id
                c.nextFocusDownId = c.id
                if (i == 0) c.nextFocusLeftId = R.id.nextBtn
            }
            nextBtn.nextFocusRightId = cards.first().id
            if (hadFocus) cards.first().requestFocus()
        }
    }

    private fun addCard(spec: CardSpec, onClick: () -> Unit, onLong: () -> Unit): View {
        val c = card(spec)
        c.setOnClickListener { onClick() }
        c.setOnLongClickListener { onLong(); true }
        row.addView(c, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(18) })
        return c
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(Look.divider)
        layoutParams = LinearLayout.LayoutParams(dp(1), dp(64)).apply {
            topMargin = dp(18); marginEnd = dp(18)
        }
    }

    private fun addPlusCard(): View {
        val box = FrameLayout(this).apply {
            background = Look.cardImage(this@MainActivity)
            foreground = Look.cardFocus(this@MainActivity)
            clipToOutline = true
            isFocusable = true
            isClickable = true
            id = View.generateViewId()
            contentDescription = "Ajouter depuis le téléphone"
        }
        val plus = ImageView(this).apply {
            setImageResource(R.drawable.ic_plus)
            imageTintList = Look.solid(Look.text)
        }
        box.addView(plus, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        box.tvFocus(1.1f, 16f)
        box.setOnClickListener { showQr() }
        row.addView(box, LinearLayout.LayoutParams(dp(99), dp(99)))
        return box
    }

    private fun onFocusMoved(now: View?) {
        if (now == null) return
        when {
            now === nextBtn || isInside(row, now) -> showRow(true)
            now === searchBtn || isInside(appList, now) -> showRow(false)
        }
    }

    private fun isInside(parent: ViewGroup, v: View): Boolean {
        var p = v.parent
        while (p != null) {
            if (p === parent) return true
            p = p.parent
        }
        return false
    }

    private fun showRow(show: Boolean) {
        if (show && !nextInMenu) return
        if (show == rowShown) return
        rowShown = show
        rowScroll.animate().cancel()
        if (show) {
            rowScroll.visibility = View.VISIBLE
            rowScroll.alpha = 0f
            rowScroll.translationY = dpf(10f)
            rowScroll.animate().alpha(1f).translationY(0f).setDuration(220).start()
        } else {
            rowScroll.animate().alpha(0f).translationY(dpf(10f)).setDuration(160)
                .withEndAction { if (!rowShown) rowScroll.visibility = View.INVISIBLE }.start()
        }
    }

    private fun pinMenu(p: Pin) {
        val actions = arrayOf("Ouvrir", "Déplacer à gauche", "Déplacer à droite", "Retirer")
        AlertDialog.Builder(this)
            .setTitle(p.title)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> Pins.open(this, p)
                    1 -> Pins.move(this, p.id, -1)
                    2 -> Pins.move(this, p.id, 1)
                    3 -> Pins.remove(this, p.id)
                }
            }
            .show()
    }

    private fun nextMenu(n: WatchNext.Item) {
        AlertDialog.Builder(this)
            .setTitle(n.title)
            .setItems(arrayOf("Ouvrir", "Épingler")) { _, which ->
                if (which == 0) WatchNext.open(this, n)
                else Pins.add(this, Pin(Pins.newId(), n.title, Apps.labelOf(this, n.pkg), n.image, n.intentUri, n.pkg, null))
            }
            .show()
    }

    // ---------- QR code pour le téléphone ----------

    private fun showQr() {
        val url = PinServer.pairingUrl(this)
        if (url == null) {
            Toast.makeText(this, "La TV ne semble pas connectée au réseau.", Toast.LENGTH_LONG).show()
            return
        }
        val dialog = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)

        val overlay = FrameLayout(this).apply { setBackgroundColor(Look.overlay) }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Look.glass(this@MainActivity, 22)
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }
        val qr = ImageView(this).apply {
            setImageBitmap(PinServer.qr(url, 400))
            setBackgroundColor(Color.WHITE)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            contentDescription = "QR code de connexion du téléphone"
        }
        panel.addView(qr, LinearLayout.LayoutParams(dp(190), dp(190)))

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(label("Connecter ton téléphone", 24f, bold = true, color = Look.text))
        col.addView(label("Scanne le code avec l'appareil photo. Ton téléphone pourra ensuite épingler des liens et taper tes recherches.", 14f,
            color = Look.text2).apply { setLineSpacing(0f, 1.25f) },
            LinearLayout.LayoutParams(dp(340), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        col.addView(label("Code valable 10 minutes, une seule fois.", 12f, color = Look.text3),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        val close = label("Fermer", 15f, bold = true).apply {
            setTextColor(Look.pillText())
            background = Look.pillButton(this@MainActivity, 19)
            gravity = Gravity.CENTER
            setPadding(dp(22), 0, dp(22), 0)
            isClickable = true
            tvFocus(1.05f, 0f)
            setOnClickListener { dialog.dismiss() }
        }
        col.addView(close, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply { topMargin = dp(20) })
        panel.addView(col, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(32)
        })

        overlay.addView(panel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ))
        dialog.setContentView(overlay)
        dialog.show()
        close.requestFocus()
    }

    // ---------- Météo ----------

    private fun loadWeather() {
        scope.launch {
            val f = try {
                withContext(Dispatchers.IO) { Weather.fetch() }
            } catch (e: Exception) {
                null
            } ?: return@launch
            weatherAt = System.currentTimeMillis()
            val rise = f.sunriseMin
            val set = f.sunsetMin
            if (rise != null && set != null) {
                Prefs.setSun(this@MainActivity, rise, set)
                if (themeChanged()) {
                    recreate()
                    return@launch
                }
            }

            findViewById<TextView>(R.id.nowLabel).text = Weather.describe(f.now.code)
            findViewById<ImageView>(R.id.wNow).setImageResource(Weather.icon(f.now.code, f.now.isDay))
            findViewById<TextView>(R.id.tNow).text = "${f.now.temp}°"
            weatherLoaded = true

            if (f.days.size >= 2) {
                findViewById<ImageView>(R.id.wToday).setImageResource(Weather.icon(f.days[0].code))
                findViewById<ImageView>(R.id.wTomorrow).setImageResource(Weather.icon(f.days[1].code))
                findViewById<TextView>(R.id.tToday).text = temps(f.days[0])
                findViewById<TextView>(R.id.tTomorrow).text = temps(f.days[1])
                applyWidgets()
            }
        }
    }

    // ---------- Agenda et notes ----------

    private fun loadAgenda() {
        val card = findViewById<LinearLayout>(R.id.agendaCard)
        if (!HomeConfig.shown(this, "agenda")) {
            card.visibility = View.GONE
            return
        }
        scope.launch {
            val events = if (Demo.on) Demo.events() else withContext(Dispatchers.IO) {
                try {
                    Agenda.upcoming(this@MainActivity, HomeConfig.agendaDays(this@MainActivity), 5)
                } catch (e: Exception) {
                    emptyList()
                }
            }
            val notes = if (Demo.on) Demo.notes() else Notes.shown(this@MainActivity).take(2)
            card.removeAllViews()
            if (events.isEmpty() && notes.isEmpty()) {
                card.visibility = View.GONE
                return@launch
            }
            if (events.isNotEmpty()) {
                card.addView(label("AGENDA", 10f, bold = true, color = Look.text3).apply { letterSpacing = 0.12f })
                for (e in events) {
                    val line = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
                    line.addView(label(Agenda.whenText(e), 12f, bold = true, color = Look.text),
                        LinearLayout.LayoutParams(dp(78), ViewGroup.LayoutParams.WRAP_CONTENT))
                    line.addView(label(e.title, 12f, color = Look.text2).apply {
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    card.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(5)
                    })
                }
            }
            if (notes.isNotEmpty()) {
                card.addView(label("NOTES", 10f, bold = true, color = Look.text3).apply { letterSpacing = 0.12f },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = if (events.isNotEmpty()) dp(10) else 0
                    })
                for (n in notes) {
                    card.addView(label(n.text, 12f, color = Look.text).apply {
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setLineSpacing(0f, 1.15f)
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(5)
                    })
                }
            }
            card.visibility = View.VISIBLE
        }
    }

    private fun temps(d: Weather.Day): CharSequence {
        val b = SpannableStringBuilder("${d.max}°  ")
        val start = b.length
        b.append("${d.min}°")
        b.setSpan(ForegroundColorSpan(Look.text3), start, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return b
    }
}
