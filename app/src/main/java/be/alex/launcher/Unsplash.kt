package be.alex.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Photos libres de droits Unsplash pour le fond d'écran. La clé reste sur la TV :
 * le téléphone passe par la TV pour chercher. Règles d'Unsplash respectées :
 * crédit du photographe affiché, et signalement du téléchargement quand une photo est choisie.
 */
object Unsplash {
    private val key get() = Keys.unsplash

    fun enabled(): Boolean = key.isNotBlank()

    fun search(q: String, page: Int = 1): JSONArray {
        val out = JSONArray()
        if (!enabled() || q.isBlank()) return out
        val j = Net.json(
            "https://api.unsplash.com/search/photos?query=${Net.enc(q)}&orientation=landscape&per_page=24&page=$page&content_filter=high",
            mapOf("Authorization" to "Client-ID $key", "Accept-Version" to "v1")
        )
        val results = j.getJSONArray("results")
        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            val urls = r.getJSONObject("urls")
            val user = r.getJSONObject("user")
            val raw = urls.str("raw")
            out.put(JSONObject().apply {
                put("id", r.str("id"))
                put("thumb", urls.str("small"))
                // Assez grand pour recadrer en 1920 x 1080 sans perte.
                put("full", "$raw&w=2560&q=85&fm=jpg")
                put("author", user.str("name"))
                put("authorUrl", user.optJSONObject("links")?.str("html") ?: "")
                put("download", r.optJSONObject("links")?.str("download_location") ?: "")
                put("color", r.str("color"))
            })
        }
        return out
    }

    /** Exigé par Unsplash quand une photo est réellement utilisée. */
    fun trackDownload(location: String) {
        if (!enabled() || !location.startsWith("https://api.unsplash.com/")) return
        try {
            Net.get(location, mapOf("Authorization" to "Client-ID $key", "Accept-Version" to "v1"))
        } catch (e: Exception) {
        }
    }

    // Crédit affiché sur l'accueil quand la photo vient d'Unsplash.
    private fun p(ctx: Context) = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    fun credit(ctx: Context): String = p(ctx).getString("photoCredit", "") ?: ""
    fun setCredit(ctx: Context, author: String) =
        p(ctx).edit().putString("photoCredit", if (author.isBlank()) "" else "Photo : ${author.take(60)} · Unsplash").apply()
}
