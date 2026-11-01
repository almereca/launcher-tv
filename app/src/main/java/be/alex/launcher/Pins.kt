package be.alex.launcher

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Pin(
    val id: String,
    val title: String,
    val subtitle: String,
    val image: String?,
    val uri: String?,
    val pkg: String?,
    val query: String?
)

/** Épingles stockées sur la TV, dans l'ordre d'affichage. */
object Pins {
    private const val KEY = "pins"
    private val main = Handler(Looper.getMainLooper())
    var onChange: (() -> Unit)? = null

    fun newId(): String = UUID.randomUUID().toString().take(8)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    @Synchronized
    fun all(ctx: Context): MutableList<Pin> {
        val raw = prefs(ctx).getString(KEY, "[]") ?: "[]"
        val out = mutableListOf<Pin>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out += Pin(
                    id = o.str("id").ifEmpty { newId() },
                    title = o.str("title"),
                    subtitle = o.str("subtitle"),
                    image = o.str("image").ifEmpty { null },
                    uri = o.str("uri").ifEmpty { null },
                    pkg = o.str("pkg").ifEmpty { null },
                    query = o.str("query").ifEmpty { null }
                )
            }
        } catch (e: Exception) {
        }
        return out
    }

    @Synchronized
    private fun save(ctx: Context, list: List<Pin>) {
        val arr = JSONArray()
        for (p in list) {
            arr.put(JSONObject().apply {
                put("id", p.id); put("title", p.title); put("subtitle", p.subtitle)
                put("image", p.image ?: ""); put("uri", p.uri ?: ""); put("pkg", p.pkg ?: ""); put("query", p.query ?: "")
            })
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
        main.post { onChange?.invoke() }
    }

    @Synchronized
    fun add(ctx: Context, pin: Pin) {
        val list = all(ctx)
        list.removeAll { (pin.uri != null && it.uri == pin.uri) || (it.title.equals(pin.title, true) && it.pkg == pin.pkg) }
        list.add(0, pin)
        save(ctx, list)
    }

    @Synchronized
    fun remove(ctx: Context, id: String) {
        val list = all(ctx)
        list.removeAll { it.id == id }
        save(ctx, list)
    }

    @Synchronized
    fun move(ctx: Context, id: String, delta: Int) {
        val list = all(ctx)
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return
        val j = (i + delta).coerceIn(0, list.size - 1)
        if (i == j) return
        val p = list.removeAt(i)
        list.add(j, p)
        save(ctx, list)
    }

    /** Remplace un favori existant (même id), sans changer sa place. */
    @Synchronized
    fun update(ctx: Context, pin: Pin) {
        val list = all(ctx)
        val i = list.indexOfFirst { it.id == pin.id }
        if (i < 0) return
        list[i] = pin
        save(ctx, list)
    }

    fun open(ctx: Context, p: Pin) {
        // Ancien favori de film ou série, sans lien exact : on le cherche une fois, puis on le garde.
        if (p.uri == null && p.query != null && p.pkg != null && p.pkg !in Catalog.youtubePackages) {
            val app = ctx.applicationContext
            Thread {
                val link = try { DeepLinks.forTitle(p.query, p.pkg) } catch (e: Exception) { null }
                val fixed = if (link != null) p.copy(uri = link) else p
                if (link != null) update(app, fixed)
                main.post { openNow(app, fixed) }
            }.start()
            return
        }
        openNow(ctx, p)
    }

    private fun openNow(ctx: Context, p: Pin) {
        val uri = p.uri
        if (uri != null) {
            if (uri.startsWith("intent:")) {
                try {
                    if (Apps.start(ctx, Intent.parseUri(uri, Intent.URI_INTENT_SCHEME))) return
                } catch (e: Exception) {
                }
            } else if (Apps.openUri(ctx, uri, p.pkg)) {
                return
            }
        }
        val pkg = p.pkg ?: return
        val q = p.query
        if (q != null) Apps.searchIn(ctx, pkg, q) else Apps.launch(ctx, pkg)
    }
}
