package be.alex.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import dadb.Dadb
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.text.Normalizer
import java.util.concurrent.Executors

/** L'écran du launcher actuellement affiché (pour le bouton Favori de la télécommande). */
object Foreground {
    private var ref: WeakReference<Activity>? = null

    fun set(a: Activity) {
        ref = WeakReference(a)
    }

    fun clear(a: Activity) {
        if (ref?.get() === a) ref = null
    }

    fun get(): Activity? = ref?.get()
}

/**
 * Télécommande du téléphone.
 *
 * Les flèches et OK sont envoyées comme de vraies touches grâce au débogage de la TV
 * (le launcher se connecte à sa propre TV, comme ton Mac le fait pour l'installer).
 * Elles marchent donc partout, y compris dans Netflix ou YouTube.
 * La première fois, la TV demande d'autoriser le débogage : il faut accepter avec la vraie télécommande.
 */
object Remote {
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var dadb: Dadb? = null

    private val codes = mapOf(
        "up" to 19, "down" to 20, "left" to 21, "right" to 22, "ok" to 23,
        "back" to 4, "home" to 3, "playpause" to 85
    )

    val keys: Set<String> = codes.keys + setOf("volup", "voldown", "mute", "fav")

    fun press(ctx: Context, key: String) {
        val app = ctx.applicationContext
        when (key) {
            "volup" -> volume(app, AudioManager.ADJUST_RAISE)
            "voldown" -> volume(app, AudioManager.ADJUST_LOWER)
            "mute" -> volume(app, AudioManager.ADJUST_TOGGLE_MUTE)
            "home" -> main.post {
                app.startActivity(
                    Intent(app, MainActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                )
            }
            "fav" -> main.post { favorite(app) }
            "back" -> main.post {
                if (!HomeService.back()) exec.execute { sendKey(app, 4) }
            }
            else -> {
                val code = codes[key] ?: return
                exec.execute {
                    if (!sendKey(app, code)) main.post { HomeService.move(key) }
                }
            }
        }
    }

    /** Touche média (pause, lecture) envoyée à l'app en cours. */
    fun media(ctx: Context, keyCode: Int) {
        val app = ctx.applicationContext
        exec.execute { sendKey(app, keyCode) }
    }

    /** Met la TV en veille (touche Veille, sinon touche Marche/Arrêt). */
    fun sleepTv(ctx: Context) {
        val app = ctx.applicationContext
        exec.execute {
            if (!sendKey(app, 223)) sendKey(app, 26)
        }
    }

    private fun volume(ctx: Context, direction: Int) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
    }

    /**
     * ★ : dans le launcher, épingle l'élément sélectionné (comme un appui long sur OK) ;
     * dans une autre app, épingle le titre en cours de lecture (Lupin sur Netflix, une vidéo YouTube…).
     */
    private fun favorite(ctx: Context) {
        val screen = Foreground.get()
        if (screen != null) {
            val v = screen.currentFocus
            if (v != null && v.isLongClickable) v.performLongClick()
            else toast(ctx, "Sélectionne un film, une série ou une vidéo pour l'épingler.")
            return
        }
        val now = MediaWatcher.nowPlaying
        val shownApp = HomeService.foreground
        if (now == null || now.title.isBlank() || (shownApp != null && shownApp != now.pkg)) {
            toast(ctx, "Lance la lecture du titre, puis appuie sur ★ pour l'épingler.")
            return
        }
        val id = Pins.newId()
        val image = now.art?.let { bmp ->
            try {
                val dir = File(ctx.filesDir, "pins").apply { mkdirs() }
                val f = File(dir, "$id.jpg")
                FileOutputStream(f).use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it) }
                f.absolutePath
            } catch (e: Exception) {
                null
            }
        } ?: now.artUri
        val label = Catalog.labelFor(now.pkg) ?: Apps.labelOf(ctx, now.pkg)
        val sub = listOfNotNull(label, now.subtitle?.takeIf { it.isNotBlank() }).joinToString(" · ")
        Pins.add(ctx, Pin(id, now.title, sub, image, null, now.pkg, now.title))
        toast(ctx, "« ${now.title} » ajouté à À suivre")
    }

    private fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

    // ---------- Recherche dans l'app ouverte ----------

    private var typed = ""

    /** Ouvre la recherche de l'app affichée (touche Recherche standard d'Android TV). */
    fun startAppSearch(ctx: Context) {
        val app = ctx.applicationContext
        typed = ""
        exec.execute { sendKey(app, 84) }
    }

    /**
     * Tape le texte du téléphone dans l'app ouverte. On remplace le champ quand l'app le permet
     * (accents compris), sinon on envoie les touches (sans accents : limite d'Android).
     */
    fun typeInApp(ctx: Context, text: String, submit: Boolean) {
        val app = ctx.applicationContext
        main.post {
            if (HomeService.setText(text)) {
                typed = text
                if (submit) exec.execute { sendKey(app, 66) }
                return@post
            }
            exec.execute {
                val clean = Normalizer.normalize(text, Normalizer.Form.NFD)
                    .replace(Regex("\\p{Mn}+"), "")
                    .filter { it in ' '..'~' }
                val old = typed
                var common = 0
                while (common < old.length && common < clean.length && old[common] == clean[common]) common++
                val erase = old.length - common
                if (erase > 0) shell(app, "input keyevent " + List(erase) { "67" }.joinToString(" "))
                val add = clean.substring(common)
                if (add.isNotEmpty()) {
                    val arg = add.replace(" ", "%s").replace("'", "'\\''")
                    shell(app, "input text '$arg'")
                }
                typed = clean
                if (submit) sendKey(app, 66)
            }
        }
    }

    @Synchronized
    private fun connection(ctx: Context): Dadb? {
        dadb?.let { return it }
        return try {
            // dadb range sa clé dans ~/.android : on lui donne le dossier privé de l'app.
            System.setProperty("user.home", ctx.filesDir.absolutePath)
            Dadb.create("127.0.0.1", 5555).also { dadb = it }
        } catch (e: Throwable) {
            null
        }
    }

    /** Envoie une vraie touche à la TV. Renvoie false si le débogage n'est pas joignable. */
    private fun sendKey(ctx: Context, code: Int): Boolean = shell(ctx, "input keyevent $code")

    /** Lance une commande sur la TV via le débogage. */
    private fun shell(ctx: Context, command: String): Boolean {
        repeat(2) {
            val d = connection(ctx) ?: return false
            try {
                d.shell(command)
                return true
            } catch (e: Throwable) {
                try {
                    d.close()
                } catch (ignored: Throwable) {
                }
                synchronized(this) { dadb = null }
            }
        }
        return false
    }
}
