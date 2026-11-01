package be.alex.launcher

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import coil.load

/**
 * Réglages sur la TV, en deux colonnes comme sur l'Apple TV : les rubriques à gauche,
 * leur contenu à droite. Le contrôle parental reste sur le téléphone, pour que les enfants
 * ne puissent pas le couper depuis la TV.
 */
class SettingsActivity : Activity() {

    private data class Category(val key: String, val title: String, val hint: String)

    private val categories = listOf(
        Category("look", "Apparence", "Fond d'écran et thème"),
        Category("home", "Accueil", "Ce qui s'affiche sur l'accueil"),
        Category("timer", "Minuteur", "Compte à rebours à l'écran"),
        Category("phones", "Téléphones", "Connexion et télécommande"),
        Category("android", "Système Android", "Réseau, son, image, apps…"),
        Category("about", "À propos", "Version et infos")
    )

    private lateinit var wall: ImageView
    private lateinit var nav: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var contentScroll: ScrollView
    private val navItems = HashMap<String, View>()
    private var current = "look"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Look.refresh(this)
        current = intent.getStringExtra("cat") ?: "look"
        build()
    }

    private fun build() {
        val root = FrameLayout(this)
        wall = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        Wallpapers.apply(this, wall)
        root.addView(wall, match())
        root.addView(View(this).apply { setBackgroundColor(Look.overlay) }, match())

        val panes = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(56), dp(40), dp(48), dp(36))
            clipChildren = false
            clipToPadding = false
        }

        // ---------- Colonne des rubriques ----------
        nav = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        nav.addView(label("Réglages", 30f, bold = true, color = Look.text).apply { setPadding(dp(14), 0, 0, dp(18)) })
        for (c in categories) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = Look.menuItem(this@SettingsActivity)
                setPadding(dp(14), dp(9), dp(14), dp(9))
                id = View.generateViewId()
                isFocusable = true
                isClickable = true
                pivotX = 0f
                tvFocus(1.03f, 0f) { has -> if (has && current != c.key) show(c.key) }
                setOnClickListener { focusFirstInContent() }
            }
            item.addView(label(c.title, 20f, bold = true).apply {
                setTextColor(Look.menuText()); isDuplicateParentStateEnabled = true
            })
            item.addView(label(c.hint, 12f).apply {
                setTextColor(Look.menuText()); alpha = 0.75f; isDuplicateParentStateEnabled = true
            })
            nav.addView(item, LinearLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
            navItems[c.key] = item
        }
        panes.addView(nav, LinearLayout.LayoutParams(dp(300), ViewGroup.LayoutParams.MATCH_PARENT))

        // ---------- Contenu ----------
        contentScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            clipChildren = false
            background = Look.glass(this@SettingsActivity, 24)
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(36), dp(28), dp(36), dp(32))
            clipChildren = false
            clipToPadding = false
        }
        contentScroll.addView(content)
        panes.addView(contentScroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(24) })
        root.addView(panes, match())
        setContentView(root)

        // En revenant du contenu vers la gauche, on retombe toujours sur la rubrique ouverte.
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { old, now ->
            if (old != null && now != null && isIn(old, content) && navItems.values.contains(now) && now !== navItems[current]) {
                navItems[current]?.requestFocus()
            }
        }

        show(current)
        val focusKey = intent.getStringExtra("focus")
        if (focusKey != null) content.findViewWithTag<View>(focusKey)?.requestFocus() ?: navItems[current]?.requestFocus()
        else navItems[current]?.requestFocus()
    }

    /** Affiche le contenu d'une rubrique ; focusKey remet la sélection sur l'élément choisi. */
    private fun show(key: String, focusKey: String? = null) {
        current = key
        // La rubrique ouverte reste bien visible, même quand on est dans le contenu.
        navItems.forEach { (k, v) -> v.alpha = if (k == key) 1f else 0.62f }
        content.removeAllViews()
        contentScroll.scrollTo(0, 0)
        val c = categories.first { it.key == key }
        content.addView(label(c.title, 26f, bold = true, color = Look.text))
        when (key) {
            "look" -> look()
            "home" -> home()
            "timer" -> timer()
            "phones" -> phones()
            "android" -> android()
            "about" -> about()
        }
        val target = navItems[key]
        content.post {
            // Depuis la rubrique, la flèche droite entre dans le contenu.
            firstFocusable(content)?.let { target?.nextFocusRightId = ensureId(it) }
            if (focusKey != null) content.findViewWithTag<View>(focusKey)?.requestFocus()
        }
    }

    // ---------- Apparence ----------

    private fun look() {
        content.addView(section("Fond d'écran"))
        val row = hRow()
        val currentWp = if (Wallpapers.usingPhoto(this) && !Demo.on) Wallpapers.PHOTO else Prefs.wallpaper(this)
        val choices = Wallpapers.all.map { it.id to it.name } +
            (if (Wallpapers.hasPhoto(this) && !Demo.on) listOf(Wallpapers.PHOTO to "Ma photo") else emptyList())
        for ((wpId, wpName) in choices) {
            val selected = wpId == currentWp
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                isFocusable = true
                isClickable = true
                clipChildren = false
                contentDescription = wpName
                tag = "wp:$wpId"
            }
            val box = FrameLayout(this).apply {
                background = Look.cardImage(this@SettingsActivity)
                foreground = if (selected) Look.selected(this@SettingsActivity) else Look.cardFocus(this@SettingsActivity)
                clipToOutline = true
                isDuplicateParentStateEnabled = true
            }
            val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            if (wpId == Wallpapers.PHOTO) {
                img.load(Wallpapers.photoFile(this)) { memoryCacheKey("thumb-" + Prefs.photoVersion(this@SettingsActivity)) }
            } else {
                img.load(Wallpapers.res(wpId, Look.dark))
            }
            box.addView(img, match())
            tile.addView(box, LinearLayout.LayoutParams(dp(150), dp(84)))
            tile.addView(label(if (selected) "$wpName  ✓" else wpName, 13f, bold = selected, color = Look.text),
                wrap().apply { topMargin = dp(8) })
            tile.tvFocus(1.08f, 12f)
            tile.setOnClickListener {
                Prefs.setWallpaper(this, wpId)
                Wallpapers.apply(this, wall)
                show("look", "wp:$wpId")
            }
            row.first.addView(tile, wrap().apply { marginEnd = dp(18) })
        }
        content.addView(row.second)
        content.addView(note("Chaque fond a une version jour et une version nuit. Pour une photo à toi ou une image Unsplash, passe par le téléphone."))

        content.addView(section("Thème"))
        val themes = pillRow()
        val currentTheme = Prefs.theme(this)
        for ((id, name) in listOf("auto" to "Automatique", "clair" to "Clair", "sombre" to "Sombre")) {
            themes.addView(pill(name, id == currentTheme, "theme:$id") {
                Prefs.setTheme(this, id)
                // Les couleurs changent partout : on redessine l'écran.
                intent.putExtra("cat", "look").putExtra("focus", "theme:$id")
                recreate()
            }, pillLp())
        }
        content.addView(themes)
        content.addView(note("Automatique : clair de ${hm(Prefs.sunrise(this))} à ${hm(Prefs.sunset(this))}, au rythme du soleil à Mons."))
    }

    // ---------- Accueil ----------

    private fun home() {
        content.addView(section("Éléments affichés"))
        for ((key, v) in HomeConfig.widgets) {
            val on = HomeConfig.shown(this, key)
            content.addView(toggle(v.first, on, "w:$key") {
                HomeConfig.set(this, key, !on)
                show("home", "w:$key")
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(4) })
        }

        content.addView(section("Agenda"))
        val days = pillRow()
        val cur = HomeConfig.agendaDays(this)
        for ((d, name) in listOf(1 to "Aujourd'hui", 3 to "3 jours", 7 to "7 jours")) {
            days.addView(pill(name, d == cur, "days:$d") {
                HomeConfig.setAgendaDays(this, d)
                show("home", "days:$d")
            }, pillLp())
        }
        content.addView(days)
        content.addView(note("Les apps de la colonne, « À suivre », les notes et les agendas se règlent depuis le téléphone."))
    }

    // ---------- Minuteur ----------

    private fun timer() {
        val left = Minuteur.remaining()
        content.addView(section(if (left > 0) "En cours" else "Lancer un minuteur"))
        if (left > 0) {
            val m = (left / 60000).toInt()
            val s = ((left / 1000) % 60).toInt()
            content.addView(label("%d:%02d restant".format(m, s), 34f, bold = true, color = Look.text).apply { fontFeatureSettings = "tnum" },
                wrap().apply { bottomMargin = dp(12) })
            content.addView(pill("Arrêter le minuteur", false, "timer:stop") {
                Minuteur.cancel(this)
                content.postDelayed({ show("timer") }, 150)
            }, pillLp())
            content.addView(section("Relancer"))
        }
        val row = pillRow()
        for (min in listOf(1, 3, 5, 10, 15, 30, 60)) {
            row.addView(pill("$min min", false, "timer:$min") {
                Minuteur.start(this, min * 60, "")
                Toast.makeText(this, "Minuteur de $min min lancé", Toast.LENGTH_SHORT).show()
                content.postDelayed({ show("timer", "timer:$min") }, 150)
            }, pillLp())
        }
        content.addView(row)
        content.addView(note("Le temps restant s'affiche en petit en haut de l'écran, même dans une app. Depuis le téléphone, tu peux aussi lui donner un nom."))
    }

    // ---------- Téléphones ----------

    private fun phones() {
        val n = PinServer.sessionCount(this)
        content.addView(section("Connexion"))
        content.addView(note(when (n) {
            0 -> "Aucun téléphone connecté pour l'instant."
            1 -> "1 téléphone connecté."
            else -> "$n téléphones connectés."
        }))
        val holder = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val actions = pillRow()
        actions.addView(pill("Connecter un téléphone", false, "qr") {
            val url = PinServer.pairingUrl(this)
            holder.removeAllViews()
            if (url == null) {
                holder.addView(note("La TV ne semble pas connectée au réseau."))
            } else {
                holder.addView(ImageView(this).apply {
                    setImageBitmap(PinServer.qr(url, 360))
                    setBackgroundColor(Color.WHITE)
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    contentDescription = "QR code de connexion"
                }, LinearLayout.LayoutParams(dp(170), dp(170)))
                holder.addView(label("Scanne ce code avec l'appareil photo du téléphone.\nValable 10 minutes, une seule fois.", 14f,
                    color = Look.text2).apply { setLineSpacing(0f, 1.3f) },
                    LinearLayout.LayoutParams(dp(300), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(22) })
            }
        }, pillLp())
        if (n > 0) {
            actions.addView(pill("Déconnecter tous les téléphones", false, "forget") {
                PinServer.forgetSessions(this)
                Toast.makeText(this, "Téléphones déconnectés", Toast.LENGTH_SHORT).show()
                show("phones", "qr")
            }, pillLp())
        }
        content.addView(actions, wrap().apply { topMargin = dp(12) })
        content.addView(holder, wrap().apply { topMargin = dp(18) })
        content.addView(section("Sur le téléphone"))
        content.addView(note("Télécommande, clavier, recherche, minuteur, favoris, contrôle parental, notes et agendas. Ajoute la page à l'écran d'accueil de l'iPhone pour l'avoir comme une app."))
    }

    // ---------- Système Android ----------

    private fun android() {
        content.addView(section("Réglages de la TV"))
        val items = listOf(
            Triple("Tous les réglages", "Le menu complet d'Android TV", Settings.ACTION_SETTINGS),
            Triple("Réseau et Internet", "Wi-Fi, Ethernet", Settings.ACTION_WIFI_SETTINGS),
            Triple("Télécommandes et accessoires", "Bluetooth, manettes, casques", Settings.ACTION_BLUETOOTH_SETTINGS),
            Triple("Son", "Sortie audio, volume", Settings.ACTION_SOUND_SETTINGS),
            Triple("Écran", "Résolution, mise en veille", Settings.ACTION_DISPLAY_SETTINGS),
            Triple("Applications", "Stockage, autorisations, désinstaller", Settings.ACTION_APPLICATION_SETTINGS),
            Triple("Date et heure", "Fuseau, format 24 h", Settings.ACTION_DATE_SETTINGS),
            Triple("Langue", "Langue du système", Settings.ACTION_LOCALE_SETTINGS),
            Triple("Stockage", "Espace libre", Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
            Triple("Accessibilité", "Sous-titres, contraste, TalkBack", Settings.ACTION_ACCESSIBILITY_SETTINGS)
        )
        // Grille de 2 colonnes.
        var line: LinearLayout? = null
        items.forEachIndexed { i, (title, sub, action) ->
            if (i % 2 == 0) {
                line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
                content.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
            }
            line!!.addView(card(title, sub, "sys:$i") { openSystem(action) },
                LinearLayout.LayoutParams(0, dp(78), 1f).apply { if (i % 2 == 0) marginEnd = dp(12) })
        }
        content.addView(note("Certaines pages n'existent pas sur toutes les TV : dans ce cas, le menu complet s'ouvre."))
    }

    private fun openSystem(action: String) {
        if (Apps.start(this, Intent(action))) return
        if (Apps.start(this, Intent(Settings.ACTION_SETTINGS))) return
        Toast.makeText(this, "Impossible d'ouvrir les réglages de la TV", Toast.LENGTH_SHORT).show()
    }

    // ---------- À propos ----------

    private fun about() {
        content.addView(section("Launcher"))
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
        content.addView(infoLine("Version", version ?: "?"))
        content.addView(infoLine("Adresse sur le réseau", PinServer.localIp() ?: "Pas de réseau"))
        content.addView(infoLine("Téléphones connectés", PinServer.sessionCount(this).toString()))
        content.addView(infoLine("Films et séries", if (Tmdb.enabled()) "TMDB, catalogue belge" else "Clé TMDB manquante"))
        content.addView(section("Plus"))
        val row = pillRow()
        row.addView(pill("Toutes les apps", false, "allapps") {
            startActivity(Intent(this, AllAppsActivity::class.java))
        }, pillLp())
        row.addView(pill("Réglages Android", false, "sysall") { openSystem(Settings.ACTION_SETTINGS) }, pillLp())
        content.addView(row)
    }

    // ---------- Petits éléments ----------

    private fun section(text: String): View = label(text.uppercase(), 12f, bold = true, color = Look.text2).apply {
        letterSpacing = 0.12f
        layoutParams = wrap().apply { topMargin = dp(26); bottomMargin = dp(12) }
    }

    private fun note(text: String): View = label(text, 13f, color = Look.text2).apply {
        setLineSpacing(0f, 1.25f)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
    }

    private fun pill(text: String, selected: Boolean, key: String, onClick: () -> Unit): View =
        label(if (selected) "$text  ✓" else text, 15f, bold = true).apply {
            setTextColor(Look.pillText())
            background = Look.pillButton(this@SettingsActivity, 19)
            gravity = Gravity.CENTER
            setPadding(dp(20), 0, dp(20), 0)
            isClickable = true
            isFocusable = true
            tag = key
            tvFocus(1.05f, 0f)
            setOnClickListener { onClick() }
        }

    /** Ligne avec un nom à gauche et « Affiché / Masqué » à droite. */
    private fun toggle(text: String, on: Boolean, key: String, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Look.result(this@SettingsActivity)
            setPadding(dp(16), 0, dp(16), 0)
            isFocusable = true
            isClickable = true
            tag = key
            tvFocus(1.02f, 0f)
            setOnClickListener { onClick() }
        }
        row.addView(label(text, 16f, bold = true).apply { setTextColor(Look.resultText()); isDuplicateParentStateEnabled = true },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(label(if (on) "Affiché" else "Masqué", 14f).apply {
            setTextColor(Look.resultSub()); isDuplicateParentStateEnabled = true
        })
        row.addView(View(this).apply {
            background = Look.toggleDot(this@SettingsActivity, on)
            isDuplicateParentStateEnabled = true
        }, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginStart = dp(10) })
        return row
    }

    private fun card(title: String, sub: String, key: String, onClick: () -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            background = Look.tile(this@SettingsActivity)
            setPadding(dp(18), 0, dp(18), 0)
            isFocusable = true
            isClickable = true
            tag = key
            tvFocus(1.04f, 0f)
            setOnClickListener { onClick() }
        }
        box.addView(label(title, 16f, bold = true).apply { setTextColor(Look.resultText()); isDuplicateParentStateEnabled = true })
        box.addView(label(sub, 12f).apply {
            setTextColor(Look.resultSub()); isDuplicateParentStateEnabled = true; maxLines = 1
        }, wrap().apply { topMargin = dp(3) })
        return box
    }

    private fun infoLine(k: String, v: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(6), 0, dp(6))
        addView(label(k, 15f, color = Look.text2), LinearLayout.LayoutParams(dp(260), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(label(v, 15f, bold = true, color = Look.text))
    }

    private fun hRow(): Pair<LinearLayout, HorizontalScrollView> {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            setPadding(dp(4), dp(8), 0, dp(4))
            addView(row)
        }
        return row to scroll
    }

    private fun pillRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        clipToPadding = false
    }

    private fun pillLp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply { marginEnd = dp(10) }
    private fun wrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun match() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    private fun focusFirstInContent() {
        firstFocusable(content)?.requestFocus()
    }

    private fun firstFocusable(v: View): View? {
        if (v.isFocusable && v.visibility == View.VISIBLE && v !== content) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) firstFocusable(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun ensureId(v: View): Int {
        if (v.id == View.NO_ID) v.id = View.generateViewId()
        return v.id
    }

    private fun isIn(v: View, parent: ViewGroup): Boolean {
        var p = v.parent
        while (p != null) {
            if (p === parent) return true
            p = p.parent
        }
        return false
    }

    private fun hm(min: Int): String = "%dh%02d".format(min / 60, min % 60)

    override fun onResume() {
        super.onResume()
        Foreground.set(this)
        if (current == "timer" || current == "phones" || current == "about") {
            val inContent = currentFocus?.let { isIn(it, content) } == true
            show(current)
            if (inContent) navItems[current]?.requestFocus()
        }
    }

    override fun onPause() {
        super.onPause()
        Foreground.clear(this)
    }
}
