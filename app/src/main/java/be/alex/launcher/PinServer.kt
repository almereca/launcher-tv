package be.alex.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.Html
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.SecureRandom

/**
 * App web servie par la TV sur le Wi-Fi de la maison, pour le téléphone ou la tablette :
 * télécommande, recherche au clavier, épingles, espace Enfants, fond d'écran et apps.
 * L'interface est dans assets/web ; ce serveur fournit les fichiers et une petite API JSON.
 *
 * Sécurité :
 *  - il faut être devant la TV : le QR code contient un code d'appairage aléatoire,
 *    valable 10 minutes et une seule fois ;
 *  - le téléphone reçoit ensuite un cookie de session (90 jours), révocable dans les réglages ;
 *  - seules les adresses du réseau local sont acceptées ;
 *  - les actions vérifient l'origine de la requête (protection contre les sites piégés).
 */
class PinServer private constructor(private val ctx: Context) : NanoHTTPD(PORT) {

    companion object {
        const val PORT = 8765
        private const val COOKIE = "lt_session"
        private const val PAIR_TTL = 10 * 60 * 1000L
        private const val MAX_SESSIONS = 12
        private const val MAX_PHOTO_BYTES = 15L * 1024 * 1024

        private var instance: PinServer? = null
        private val rng = SecureRandom()
        private val main = Handler(Looper.getMainLooper())

        @Volatile private var pairCode: String? = null
        @Volatile private var pairUntil = 0L

        /** Appelé sur le fil principal quand un téléphone vient de se connecter. */
        var onPaired: (() -> Unit)? = null

        /** Appelé sur le fil principal quand le fond d'écran a été changé depuis un téléphone. */
        var onWallpaper: (() -> Unit)? = null

        /** Appelé sur le fil principal quand la liste des apps a été modifiée depuis un téléphone. */
        var onApps: (() -> Unit)? = null

        /** Appelé sur le fil principal quand l'accueil (éléments, notes, agendas) a été modifié. */
        var onHome: (() -> Unit)? = null

        fun ensure(ctx: Context) {
            Keys.init(ctx)
            if (instance?.isAlive == true) return
            try {
                instance = PinServer(ctx.applicationContext).also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
            } catch (e: Exception) {
                instance = null
            }
        }

        private fun random(bytes: Int): String {
            val b = ByteArray(bytes)
            rng.nextBytes(b)
            val alphabet = "abcdefghijkmnopqrstuvwxyz23456789"
            return b.joinToString("") { alphabet[(it.toInt() and 0xFF) % alphabet.length].toString() }
        }

        /** Nouveau code à usage unique, affiché dans le QR code. */
        fun pairingUrl(ctx: Context): String? {
            val ip = localIp() ?: return null
            val code = random(20)
            pairCode = code
            pairUntil = System.currentTimeMillis() + PAIR_TTL
            return "http://$ip:$PORT/p/$code"
        }

        private fun prefs(ctx: Context) = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)

        @Synchronized
        private fun sessions(ctx: Context): MutableList<String> =
            (prefs(ctx).getString("sessions", "") ?: "").split(',').filter { it.length >= 20 }.toMutableList()

        @Synchronized
        private fun saveSessions(ctx: Context, list: List<String>) =
            prefs(ctx).edit().putString("sessions", list.takeLast(MAX_SESSIONS).joinToString(",")).apply()

        fun sessionCount(ctx: Context): Int = sessions(ctx).size

        fun forgetSessions(ctx: Context) = saveSessions(ctx, emptyList())

        fun localIp(): String? = try {
            NetworkInterface.getNetworkInterfaces()?.toList()
                ?.filter { it.isUp && !it.isLoopback }
                ?.flatMap { it.inetAddresses.toList() }
                ?.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                ?.hostAddress
        } catch (e: Exception) {
            null
        }

        fun qr(text: String, size: Int): Bitmap {
            val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
            val px = IntArray(size * size)
            for (y in 0 until size) for (x in 0 until size) {
                px[y * size + x] = if (m.get(x, y)) Color.BLACK else Color.WHITE
            }
            return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
        }
    }

    override fun serve(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        // 1. Réseau local uniquement.
        val remote = try {
            InetAddress.getByName(session.remoteIpAddress)
        } catch (e: Exception) {
            null
        }
        if (remote == null || !(remote.isSiteLocalAddress || remote.isLoopbackAddress)) {
            return text(NanoHTTPD.Response.Status.FORBIDDEN, "Accès réservé au réseau de la maison.")
        }

        val path = session.uri.trimEnd('/').ifEmpty { "/" }

        // Fichiers publics : icônes et feuille de style (rien de personnel dedans).
        when (path) {
            "/icon.png" -> return raw(R.raw.touch_icon, "image/png")
            "/icon-512.png" -> return raw(R.raw.touch_icon_512, "image/png")
            "/app.css" -> return asset("web/app.css", "text/css; charset=utf-8")
        }
        // Le manifest est chargé sans cookie par le navigateur : la session est passée dans l'adresse.
        if (path == "/manifest.webmanifest") {
            @Suppress("DEPRECATION")
            val sid = session.parms?.get("s")
            if (sid == null || sid !in sessions(ctx)) return text(NanoHTTPD.Response.Status.FORBIDDEN, "{}")
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/manifest+json", manifest(sid))
        }

        // 2. Qui est-ce ? Cookie, adresse de l'app épinglée (/s/<session>), ou QR code (/p/<code>).
        val cookie = session.cookies.read(COOKIE)
        var authed = cookie != null && cookie in sessions(ctx)
        var sid: String? = if (authed) cookie else null
        var newCookie: String? = null

        if (path.startsWith("/s/")) {
            val fromUrl = path.removePrefix("/s/").substringBefore('/')
            if (fromUrl in sessions(ctx)) {
                if (!authed) newCookie = fromUrl
                authed = true
                sid = fromUrl
            }
        }
        if (path.startsWith("/p/")) {
            val code = path.removePrefix("/p/")
            val expected = pairCode
            if (expected != null && code == expected && System.currentTimeMillis() < pairUntil) {
                pairCode = null
                val fresh = random(32)
                saveSessions(ctx, sessions(ctx) + fresh)
                newCookie = fresh
                authed = true
                sid = fresh
                main.post { onPaired?.invoke() }
            } else if (!authed) {
                return html(NanoHTTPD.Response.Status.FORBIDDEN, message("Code expiré",
                    "Ce code a déjà servi ou a expiré. Sur la TV, ouvre la carte + de la rangée À suivre et rescanne le QR code."))
            }
        }
        if (!authed) {
            return html(NanoHTTPD.Response.Status.FORBIDDEN, message("Téléphone non connecté",
                "Sur la TV, ouvre la carte + de la rangée À suivre et scanne le QR code."))
        }

        // 3. Les actions doivent venir de cette app (pas d'un autre site).
        val uploads = HashMap<String, String>()
        if (session.method == NanoHTTPD.Method.POST) {
            val origin = session.headers["origin"]
            val host = session.headers["host"]
            if (origin != null && host != null && Uri.parse(origin).authority != host) {
                return text(NanoHTTPD.Response.Status.FORBIDDEN, "Origine refusée.")
            }
            try {
                session.parseBody(uploads)
            } catch (e: Exception) {
            }
        }
        @Suppress("DEPRECATION")
        val params: Map<String, String> = session.parms ?: emptyMap()

        val response: NanoHTTPD.Response = when {
            path == "/" || path.startsWith("/s/") || path.startsWith("/p/") -> {
                val page = readAsset("web/index.html").replace("__SID__", sid ?: "")
                html(NanoHTTPD.Response.Status.OK, page)
            }
            path == "/app.js" -> asset("web/app.js", "application/javascript; charset=utf-8")
            path == "/api/state" -> json(state())
            path.startsWith("/wp/") && path.endsWith(".webp") -> {
                val id = path.removePrefix("/wp/").removeSuffix(".webp")
                if (Wallpapers.all.none { it.id == id }) text(NanoHTTPD.Response.Status.NOT_FOUND, "Inconnu")
                else NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK, "image/webp",
                    ctx.resources.openRawResource(Wallpapers.res(id, Look.dark))).apply {
                    addHeader("Cache-Control", "private, max-age=3600")
                }
            }
            path == "/photo.jpg" -> {
                val f = Wallpapers.photoFile(ctx)
                if (!f.exists()) text(NanoHTTPD.Response.Status.NOT_FOUND, "Aucune photo")
                else NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "image/jpeg", f.inputStream(), f.length())
            }
            path.startsWith("/wn/") && path.endsWith(".img") -> watchNextImage(path.removePrefix("/wn/").removeSuffix(".img").toIntOrNull() ?: -1)
            path.startsWith("/appicon/") -> appIcon(path.removePrefix("/appicon/").removeSuffix(".png"))
            session.method != NanoHTTPD.Method.POST -> text(NanoHTTPD.Response.Status.NOT_FOUND, "Introuvable")

            // ---------- Actions ----------
            path == "/key" -> {
                val k = params["k"].orEmpty()
                if (k in Remote.keys) Remote.press(ctx, k)
                ok()
            }
            path == "/appsearch" -> {
                // Bouton clavier : recherche du launcher si on est dessus, sinon recherche de l'app ouverte.
                if (Foreground.get() != null) main.post { SearchActivity.fromPhone(ctx, "", submit = false) }
                else Remote.startAppSearch(ctx)
                ok()
            }
            path == "/type" || path == "/go" -> {
                val q = params["q"].orEmpty().take(120)
                val submit = path == "/go"
                if (Foreground.get() != null || params["target"] == "launcher") {
                    main.post { SearchActivity.fromPhone(ctx, q, submit) }
                } else {
                    Remote.typeInApp(ctx, q, submit)
                }
                ok()
            }
            path == "/trailer/vo" -> {
                val on = params["on"] == "1"
                Prefs.setTrailerVO(ctx, on)
                ok(if (on) "Bandes-annonces en version originale." else "Bandes-annonces en français d'abord.")
            }
            path == "/agenda/days" -> {
                HomeConfig.setAgendaDays(ctx, params["days"]?.toIntOrNull() ?: 1)
                main.post { onHome?.invoke() }
                ok()
            }
            path == "/timer" -> {
                val sec = params["seconds"]?.toIntOrNull()
                if (sec == null || sec <= 0) {
                    Minuteur.cancel(ctx)
                    ok("Minuteur arrêté.")
                } else {
                    Minuteur.start(ctx, sec, params["label"].orEmpty())
                    ok("Minuteur lancé sur la TV.")
                }
            }
            path == "/rating" -> {
                val r = Tmdb.rating(params["type"].orEmpty(), params["id"]?.toIntOrNull() ?: 0)
                json(JSONObject().put("ok", true).apply { Discover.ratingJson(this, r) })
            }
            path == "/keys" -> {
                // Chaque clé envoyée est testée auprès de son service avant d'être gardée.
                val res = JSONObject()
                var saved = 0
                for (name in listOf("tmdb", "youtube", "unsplash")) {
                    val v = params[name]?.trim().orEmpty()
                    if (v.isEmpty()) continue
                    val problem = Keys.test(name, v)
                    if (problem == null) { Keys.set(name, v); saved++ }
                    res.put(name, problem ?: "ok")
                }
                if (params["clear"] in setOf("tmdb", "youtube", "unsplash")) {
                    Keys.set(params["clear"]!!, "")
                    res.put(params["clear"]!!, "cleared")
                }
                if (saved > 0) Discover.reset()
                json(JSONObject().put("ok", true).put("results", res).put("keys", Keys.state()))
            }
            path == "/taste" -> {
                if (params["action"] == "reset") {
                    Taste.reset(ctx); ok("Préférences effacées.")
                } else try {
                    Taste.set(ctx, JSONObject(params["item"].orEmpty()), params["state"].orEmpty())
                    ok()
                } catch (e: Exception) {
                    ok("Réponse illisible.")
                }
            }
            path == "/discover" -> json(JSONObject().put("ok", true).put("sections", Discover.load(ctx, params["fresh"] == "1")))
            path == "/find" -> json(JSONObject().put("ok", true).put("results", Finder.find(ctx, params["q"].orEmpty())))
            path == "/act" -> ok(Finder.act(ctx, params["action"].orEmpty(), params["item"].orEmpty()))
            path == "/home" -> {
                HomeConfig.set(ctx, params["id"].orEmpty(), params["on"] == "1")
                main.post { onHome?.invoke() }
                ok()
            }
            path == "/notes" -> {
                try {
                    val arr = JSONArray(params["data"].orEmpty())
                    Notes.save(ctx, (0 until arr.length()).map { i ->
                        val o = arr.getJSONObject(i)
                        Notes.Note(o.str("id").ifBlank { Pins.newId() }, o.str("text"), o.optBoolean("shown", true))
                    })
                    main.post { onHome?.invoke() }
                    ok()
                } catch (e: Exception) {
                    ok("Impossible d'enregistrer les notes.")
                }
            }
            path == "/calendars" -> {
                try {
                    val arr = JSONArray(params["data"].orEmpty())
                    val list = (0 until arr.length()).map { i ->
                        val o = arr.getJSONObject(i)
                        Agenda.Source(o.str("id").ifBlank { Pins.newId() }, o.str("name").ifBlank { "Agenda" }, o.str("url").trim(), o.optBoolean("shown", true))
                    }.filter { it.url.startsWith("http") || it.url.startsWith("webcal") }
                    Agenda.saveSources(ctx, list)
                    main.post { onHome?.invoke() }
                    ok()
                } catch (e: Exception) {
                    ok("Impossible d'enregistrer les agendas.")
                }
            }
            path == "/unsplash" -> {
                val res = try {
                    Unsplash.search(params["q"].orEmpty(), params["page"]?.toIntOrNull() ?: 1)
                } catch (e: Exception) {
                    JSONArray()
                }
                json(JSONObject().put("ok", true).put("results", res))
            }
            path == "/pin/add" -> ok(add(params["link"].orEmpty().trim(), params["title"].orEmpty().trim()))
            path == "/pin/del" -> {
                params["id"]?.let { Pins.remove(ctx, it) }
                ok("Retiré de la TV.")
            }
            path == "/pin/move" -> {
                val id = params["id"].orEmpty()
                val delta = params["delta"]?.toIntOrNull() ?: 0
                if (id.isNotEmpty() && delta != 0) Pins.move(ctx, id, delta)
                ok()
            }
            path == "/wallpaper" -> {
                val msg = savePhoto(uploads["photo"], params["dim"])
                Unsplash.setCredit(ctx, params["author"].orEmpty())
                params["download"]?.takeIf { it.isNotBlank() }?.let { loc -> Thread { Unsplash.trackDownload(loc) }.start() }
                main.post { onWallpaper?.invoke() }
                ok(msg)
            }
            path == "/wallpaper/set" -> {
                val id = params["id"].orEmpty()
                val name = when {
                    id == Wallpapers.PHOTO && Wallpapers.hasPhoto(ctx) -> "Ta photo"
                    else -> Wallpapers.all.firstOrNull { it.id == id }?.name
                }
                if (name == null) ok("Fond inconnu.") else {
                    Prefs.setWallpaper(ctx, id)
                    main.post { onWallpaper?.invoke() }
                    ok("« $name » est en fond d'écran.")
                }
            }
            path == "/wallpaper/dim" -> {
                params["dim"]?.toIntOrNull()?.let { Prefs.setDim(ctx, it) }
                main.post { onWallpaper?.invoke() }
                ok()
            }
            path == "/theme" -> {
                val mode = params["mode"].orEmpty()
                if (mode in setOf("auto", "clair", "sombre")) {
                    Prefs.setTheme(ctx, mode)
                    main.post { onWallpaper?.invoke() }
                }
                ok()
            }
            path == "/menu" -> {
                try {
                    val arr = JSONArray(params["data"].orEmpty())
                    val items = (0 until arr.length()).map { i ->
                        val o = arr.getJSONObject(i)
                        Apps.MenuItem(o.str("pkg"), o.str("name").ifBlank { null }, o.optBoolean("logo", false))
                    }
                    Apps.saveMenu(ctx, items)
                    main.post { onApps?.invoke() }
                    ok()
                } catch (e: Exception) {
                    ok("Impossible d'enregistrer la liste.")
                }
            }
            path == "/kids" -> ok(kids(params))
            path == "/defis" -> ok(when (params["action"]) {
                "now" -> { QuizActivity.show(ctx); "Défi lancé sur la TV." }
                "skip" -> { QuizActivity.skip(); "Défi passé." }
                else -> {
                    Defis.save(ctx,
                        params["on"]?.let { it == "1" },
                        params["every"]?.toIntOrNull(),
                        params["count"]?.toIntOrNull(),
                        params["level"],
                        params["types"]?.split(',')?.toSet(),
                        params["ops"]?.split(',')?.toSet(),
                        params["numeric"]?.let { it == "1" })
                    if (Defis.enabled(ctx)) "Un défi toutes les ${Defis.interval(ctx)} minutes." else "Défis désactivés."
                }
            })
            path == "/remote/apps" -> {
                val known = Apps.launchables(ctx).map { it.pkg }.toSet()
                Prefs.setRemoteApps(ctx, params["pkgs"].orEmpty().split(',').filter { it in known })
                ok()
            }
            path == "/launch" -> {
                val pkg = params["pkg"].orEmpty()
                val app = Apps.launchables(ctx).firstOrNull { it.pkg == pkg }
                if (app == null) ok("App introuvable.") else {
                    main.post { Apps.launch(ctx, pkg) }
                    ok()
                }
            }
            else -> text(NanoHTTPD.Response.Status.NOT_FOUND, "Introuvable")
        }
        newCookie?.let {
            response.addHeader("Set-Cookie", "$COOKIE=$it; Max-Age=7776000; Path=/; HttpOnly; SameSite=Strict")
        }
        return response
    }

    /** Image d'un élément « Watch Next » stockée dans Android (adresse content://). */
    private fun watchNextImage(i: Int): NanoHTTPD.Response {
        val uri = WatchNext.load(ctx).getOrNull(i)?.image ?: return text(NanoHTTPD.Response.Status.NOT_FOUND, "Pas d'image")
        return try {
            val bytes = ctx.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { it.readBytes() }
                ?: return text(NanoHTTPD.Response.Status.NOT_FOUND, "Pas d'image")
            NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "image/jpeg", ByteArrayInputStream(bytes), bytes.size.toLong()).apply {
                addHeader("Cache-Control", "private, max-age=600")
            }
        } catch (e: Exception) {
            text(NanoHTTPD.Response.Status.NOT_FOUND, "Pas d'image")
        }
    }

    // ---------- Données pour l'app du téléphone ----------

    private fun state(): JSONObject {
        val installed = Apps.launchables(ctx)
        val menu = Apps.menu(ctx, installed)
        return JSONObject().apply {
            put("dark", Look.dark)
            put("theme", Prefs.theme(ctx))
            put("wallpaper", if (Wallpapers.usingPhoto(ctx)) Wallpapers.PHOTO else Prefs.wallpaper(ctx))
            put("photo", Wallpapers.hasPhoto(ctx))
            put("photoVersion", Prefs.photoVersion(ctx))
            put("dim", Prefs.dim(ctx))
            put("wallpapers", JSONArray().apply {
                Wallpapers.all.forEach { w -> put(JSONObject().put("id", w.id).put("name", w.name)) }
            })
            put("pins", JSONArray().apply {
                Pins.all(ctx).forEach { p ->
                    put(JSONObject().put("id", p.id).put("title", p.title).put("subtitle", p.subtitle).put("image", p.image ?: ""))
                }
            })
            put("menu", JSONArray().apply {
                menu.forEach { m ->
                    put(JSONObject().put("pkg", m.pkg).put("label", m.label).put("original", m.original))
                }
            })
            put("others", JSONArray().apply {
                if (menu.none { it.pkg == Apps.NEXT }) put(JSONObject().put("pkg", Apps.NEXT).put("label", Apps.NEXT_LABEL))
                installed.filter { o -> menu.none { it.pkg == o.pkg } }.forEach { o ->
                    put(JSONObject().put("pkg", o.pkg).put("label", Apps.defaultLabel(o.pkg, installed)))
                }
            })
            put("remoteApps", JSONArray().apply {
                Prefs.remoteApps(ctx).forEach { pkg ->
                    if (installed.any { it.pkg == pkg }) {
                        val label = menu.firstOrNull { it.pkg == pkg }?.label ?: Apps.defaultLabel(pkg, installed)
                        put(JSONObject().put("pkg", pkg).put("label", label))
                    }
                }
            })
            put("kids", Kids.state(ctx))
            put("defis", Defis.state(ctx))
            put("keys", Keys.state())
            put("home", HomeConfig.state(ctx))
            put("agendaDays", HomeConfig.agendaDays(ctx))
            put("trailerVO", Prefs.trailerVO(ctx))
            put("timer", Minuteur.state())
            put("notes", JSONArray().apply {
                Notes.all(ctx).forEach { n -> put(JSONObject().put("id", n.id).put("text", n.text).put("shown", n.shown)) }
            })
            put("calendars", JSONArray().apply {
                Agenda.sources(ctx).forEach { c -> put(JSONObject().put("id", c.id).put("name", c.name).put("url", c.url).put("shown", c.shown)) }
            })
            put("unsplash", Unsplash.enabled())
            put("credit", Unsplash.credit(ctx))
            val shown = if (Foreground.get() != null) null else HomeService.foreground
            put("app", shown?.let { Catalog.labelFor(it) ?: Apps.labelOf(ctx, it) } ?: "")
        }
    }

    private fun kids(p: Map<String, String>): String {
        return when (p["action"]) {
            "in" -> {
                val min = p["minutes"]?.toIntOrNull() ?: return "Durée invalide."
                Kids.sleepIn(ctx, min)
                "La télé s'éteindra dans ${duration(min)}."
            }
            "at" -> {
                val t = p["time"].orEmpty()
                val daily = p["daily"] == "1"
                Kids.sleepAtTime(ctx, t, daily)
                if (daily) "La télé s'éteindra tous les jours à $t." else "La télé s'éteindra à $t."
            }
            "end" -> {
                val on = p["on"] == "1"
                Kids.setEndOfContent(ctx, on)
                if (on) "La télé s'éteindra à la fin du contenu en cours." else "Extinction en fin de contenu annulée."
            }
            "cancel" -> { Kids.cancelTimers(ctx); "Extinction annulée." }
            "canceldaily" -> { Kids.cancelDaily(ctx); "Extinction quotidienne supprimée." }
            "now" -> { Kids.sleepNow(ctx); "La télé s'éteint." }
            "lock" -> {
                val on = p["on"] == "1"
                Kids.setLock(ctx, on)
                if (on) "Code activé : ${Kids.code(ctx)}" else "Code désactivé."
            }
            "newcode" -> "Nouveau code : ${Kids.newCode(ctx)}"
            "locknow" -> { Kids.lockNow(ctx); "La télé est verrouillée." }
            "unlock" -> { Kids.unlock(ctx); "La télé est déverrouillée." }
            else -> "Action inconnue."
        }
    }

    private fun duration(min: Int): String = when {
        min < 60 -> "$min minutes"
        min % 60 == 0 -> "${min / 60} h"
        else -> "${min / 60} h ${"%02d".format(min % 60)}"
    }

    private fun appIcon(pkg: String): NanoHTTPD.Response {
        if (Apps.launchables(ctx).none { it.pkg == pkg }) return text(NanoHTTPD.Response.Status.NOT_FOUND, "Inconnue")
        val d = Apps.icon(ctx, pkg) ?: return text(NanoHTTPD.Response.Status.NOT_FOUND, "Pas d'icône")
        val size = 96
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, size, size)
        d.draw(Canvas(b))
        val out = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.PNG, 100, out)
        val bytes = out.toByteArray()
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "image/png", ByteArrayInputStream(bytes), bytes.size.toLong()).apply {
            addHeader("Cache-Control", "private, max-age=86400")
        }
    }

    // ---------- Actions ----------

    /** Reçoit la photo déjà recadrée en 1920 x 1080 par le téléphone. */
    private fun savePhoto(tmpPath: String?, dimParam: String?): String {
        val tmp = tmpPath?.let { File(it) } ?: return "Aucune photo reçue."
        if (!tmp.exists() || tmp.length() == 0L) return "Aucune photo reçue."
        if (tmp.length() > MAX_PHOTO_BYTES) return "Photo trop lourde."
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(tmp.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return "Ce fichier n'est pas une image."
        return try {
            tmp.copyTo(Wallpapers.photoFile(ctx), overwrite = true)
            dimParam?.toIntOrNull()?.let { Prefs.setDim(ctx, it) }
            Prefs.setWallpaper(ctx, Wallpapers.PHOTO)
            Prefs.bumpPhotoVersion(ctx)
            main.post { onWallpaper?.invoke() }
            "Ta photo est en fond d'écran."
        } catch (e: Exception) {
            "La TV n'a pas pu enregistrer la photo."
        }
    }

    private fun add(link: String, typedTitle: String): String {
        if (!link.startsWith("http")) return "Colle un lien qui commence par https://"
        val host = Uri.parse(link).host ?: return "Lien illisible."
        val entry = Catalog.forHost(host) ?: return "Plateforme non reconnue pour ce lien."
        val installed = Apps.launchables(ctx)
        val pkg = Apps.resolve(entry, installed)

        if (entry == Catalog.YOUTUBE) {
            val r = try {
                YouTube.fromLink(link)
            } catch (e: Exception) {
                null
            } ?: return "Lien YouTube non reconnu (vidéo, chaîne ou @pseudo)."
            val title = typedTitle.ifEmpty { r.title }
            val sub = if (r.kind == "channel") "Chaîne YouTube" else "YouTube · ${r.channel}"
            Pins.add(ctx, Pin(Pins.newId(), title, sub, r.thumb, YouTube.uri(r), pkg, null))
            return "« $title » est sur la TV."
        }

        var title = typedTitle
        var image: String? = null
        try {
            val page = Net.get(link, timeoutMs = 5000)
            if (title.isEmpty()) title = meta(page, "og:title") ?: tag(page, "title") ?: ""
            image = meta(page, "og:image")
        } catch (e: Exception) {
        }
        if (title.isEmpty()) title = "Lien ${entry.label}"
        title = title.replace(Regex("\\s*[|–-]\\s*(Netflix|Disney\\+|Prime Video|Max|Crunchyroll|Auvio|ARTE).*$", RegexOption.IGNORE_CASE), "").trim()

        Pins.add(ctx, Pin(Pins.newId(), title, entry.label, image, link, pkg, null))
        return "« $title » est sur la TV."
    }

    private fun meta(page: String, prop: String): String? {
        val r1 = Regex("<meta[^>]+(?:property|name)=[\"']$prop[\"'][^>]+content=[\"']([^\"']+)", RegexOption.IGNORE_CASE)
        val r2 = Regex("<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+(?:property|name)=[\"']$prop[\"']", RegexOption.IGNORE_CASE)
        val v = r1.find(page)?.groupValues?.get(1) ?: r2.find(page)?.groupValues?.get(1)
        return v?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString().trim() }?.ifEmpty { null }
    }

    private fun tag(page: String, name: String): String? =
        Regex("<$name[^>]*>([^<]+)</$name>", RegexOption.IGNORE_CASE).find(page)?.groupValues?.get(1)
            ?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString().trim() }?.ifEmpty { null }

    // ---------- Réponses ----------

    private fun readAsset(name: String): String = ctx.assets.open(name).bufferedReader().use { it.readText() }

    private fun asset(name: String, mime: String): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, mime, readAsset(name)).apply {
            addHeader("Cache-Control", "no-cache")
        }

    private fun raw(res: Int, mime: String): NanoHTTPD.Response =
        NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK, mime, ctx.resources.openRawResource(res)).apply {
            addHeader("Cache-Control", "public, max-age=86400")
        }

    private fun json(o: JSONObject): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json; charset=utf-8", o.toString()).apply {
            addHeader("Cache-Control", "no-store")
        }

    private fun ok(msg: String = ""): NanoHTTPD.Response = json(JSONObject().put("ok", true).put("msg", msg))

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    private fun html(status: NanoHTTPD.Response.Status, body: String): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(status, "text/html; charset=utf-8", body).apply {
            addHeader("Cache-Control", "no-store")
            addHeader("X-Frame-Options", "DENY")
            addHeader("Referrer-Policy", "no-referrer")
        }

    private fun text(status: NanoHTTPD.Response.Status, body: String): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(status, "text/plain; charset=utf-8", body)

    private fun manifest(sid: String): String = """
        {"name":"Télécommande TV","short_name":"TV","lang":"fr",
         "start_url":"/s/$sid","scope":"/","display":"standalone",
         "background_color":"#000000","theme_color":"#000000",
         "icons":[{"src":"/icon.png","sizes":"180x180","type":"image/png"},
                  {"src":"/icon-512.png","sizes":"512x512","type":"image/png"}]}
    """.trimIndent()

    private fun message(title: String, text: String) = """
        <!doctype html><html lang=fr><head><meta charset=utf-8>
        <meta name=viewport content='width=device-width,initial-scale=1'>
        <title>${esc(title)}</title><link rel=stylesheet href='/app.css'></head>
        <body class=msg><main><h1>${esc(title)}</h1><p>${esc(text)}</p></main></body></html>
    """.trimIndent()
}
