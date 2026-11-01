package be.alex.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject

/**
 * Lien exact vers un film ou une série dans l'app de la plateforme.
 * TMDB donne l'identifiant Wikidata du titre, et Wikidata donne les identifiants
 * Netflix, Disney+, Prime Video et Max. On en fait un lien que l'app de la TV comprend.
 * Sans identifiant connu (Auvio, Arte, titres récents), on renvoie null et l'app s'ouvre simplement.
 */
object DeepLinks {
    private val cache = HashMap<String, List<String>>()

    /** Lien (URI d'intent ou https) vers ce titre dans l'app pkg, ou null. */
    fun find(r: Tmdb.Result, entry: AppEntry, pkg: String): String? {
        val urls = platformUrls(r)
        if (urls.isEmpty()) return null
        return when (entry) {
            Catalog.NETFLIX -> {
                val id = urls.firstOrNull { "netflix." in it }?.let { Regex("(\\d{5,})").find(it)?.value } ?: return null
                netflix(pkg, id)
            }
            Catalog.DISNEY -> urls.firstOrNull { "disneyplus." in it }?.let { view(it, pkg) }
            Catalog.PRIME -> urls.firstOrNull { "primevideo." in it || "amazon." in it }?.let { view(it, pkg) }
            Catalog.HBO -> urls.firstOrNull { "max.com" in it || "hbomax." in it }?.let { view(it, pkg) }
            else -> null
        }
    }

    /** Retrouve le titre dans TMDB puis cherche le lien (pour les anciens favoris enregistrés sans lien). */
    fun forTitle(title: String, pkg: String): String? {
        val entry = Catalog.favorites.firstOrNull { pkg in it.packages } ?: return null
        val results = try { Tmdb.search(title) } catch (e: Exception) { emptyList() }
        val r = results.firstOrNull { it.title.equals(title, ignoreCase = true) } ?: results.firstOrNull() ?: return null
        return find(r, entry, pkg)
    }

    /**
     * Netflix sur Android TV : la page du titre s'ouvre seulement avec l'activité principale
     * et l'extra source=30 (format documenté par les intégrations domotiques).
     */
    private fun netflix(pkg: String, id: String): String {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("http://www.netflix.com/title/$id"))
        if (pkg == "com.netflix.ninja") {
            i.setClassName(pkg, "com.netflix.ninja.MainActivity")
            i.putExtra("source", "30")
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        } else {
            i.setPackage(pkg)
        }
        return i.toUri(Intent.URI_INTENT_SCHEME)
    }

    private fun view(url: String, pkg: String): String =
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg).toUri(Intent.URI_INTENT_SCHEME)

    /** Adresses du titre sur les plateformes, d'après Wikidata (mises en cache). */
    private fun platformUrls(r: Tmdb.Result): List<String> {
        val qid = Tmdb.wikidataId(r) ?: return emptyList()
        synchronized(cache) { cache[qid]?.let { return it } }
        val query = """
            SELECT ?fmt ?id WHERE {
              wd:$qid ?p ?id .
              ?prop wikibase:directClaim ?p ; wdt:P1630 ?fmt .
              FILTER(CONTAINS(?fmt, "netflix.") || CONTAINS(?fmt, "disneyplus.") || CONTAINS(?fmt, "primevideo.")
                || CONTAINS(?fmt, "max.com") || CONTAINS(?fmt, "hbomax."))
            }
        """.trimIndent()
        val out = try {
            val j = JSONObject(Net.get(
                "https://query.wikidata.org/sparql?format=json&query=${Net.enc(query)}",
                mapOf("User-Agent" to "LauncherTV/2.0 (usage personnel)", "Accept" to "application/sparql-results+json"),
                10_000
            ))
            val rows = j.getJSONObject("results").getJSONArray("bindings")
            (0 until rows.length()).mapNotNull { i ->
                val b = rows.getJSONObject(i)
                val fmt = b.optJSONObject("fmt")?.str("value") ?: return@mapNotNull null
                val id = b.optJSONObject("id")?.str("value") ?: return@mapNotNull null
                if ("$1" !in fmt) null else fmt.replace("$1", id)
            }.distinct()
        } catch (e: Exception) {
            return emptyList()
        }
        synchronized(cache) { cache[qid] = out }
        return out
    }
}
