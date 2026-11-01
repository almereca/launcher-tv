package be.alex.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ce que tu as répondu aux cartes de « À regarder », gardé sur la TV (rien ne part ailleurs) :
 *  - want : ça me tente (va dans « Ma liste ») ;
 *  - no : pas intéressé ;
 *  - seen : déjà vu ;
 *  - liked : j'ai adoré (sert aux suggestions « Pour toi ») ;
 *  - nogenre : ne me montre pas des trucs comme ça.
 */
object Taste {
    val STATES = setOf("want", "no", "seen", "liked", "nogenre")

    data class Entry(val key: String, val state: String, val time: Long, val item: JSONObject) {
        val genres: Set<Int> get() = item.optJSONArray("genres")?.let { a -> (0 until a.length()).map { a.optInt(it) }.toSet() } ?: emptySet()
    }

    private fun p(ctx: Context) = ctx.getSharedPreferences("taste", Context.MODE_PRIVATE)

    fun key(item: JSONObject) = "${item.str("type")}:${item.optInt("id")}"

    @Synchronized
    fun all(ctx: Context): Map<String, Entry> = try {
        val o = JSONObject(p(ctx).getString("items", "{}") ?: "{}")
        o.keys().asSequence().associateWith { k ->
            val e = o.getJSONObject(k)
            Entry(k, e.str("s"), e.optLong("t"), e.optJSONObject("item") ?: JSONObject())
        }
    } catch (e: Exception) {
        emptyMap()
    }

    /** state vide ou "reset" : on oublie la réponse. */
    @Synchronized
    fun set(ctx: Context, item: JSONObject, state: String) {
        val o = try { JSONObject(p(ctx).getString("items", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        val k = key(item)
        if (state !in STATES) o.remove(k)
        else {
            // On garde l'essentiel du titre pour pouvoir l'afficher dans « Ma liste ».
            val keep = JSONObject()
            for (f in listOf("kind", "id", "type", "title", "year", "overview", "poster", "backdrop", "providers", "watch",
                "subtitle", "genres", "age", "ageNum", "descriptors")) {
                if (item.has(f)) keep.put(f, item.get(f))
            }
            o.put(k, JSONObject().put("s", state).put("t", System.currentTimeMillis()).put("item", keep))
        }
        // Pas plus de 600 réponses : on oublie les plus anciennes.
        if (o.length() > 600) {
            val oldest = o.keys().asSequence().sortedBy { o.getJSONObject(it).optLong("t") }.take(o.length() - 600).toList()
            oldest.forEach { o.remove(it) }
        }
        p(ctx).edit().putString("items", o.toString()).apply()
    }

    /** Faut-il cacher ce titre des rangées d'idées ? */
    fun hidden(entries: Map<String, Entry>, item: JSONObject): Boolean {
        if (entries.containsKey(key(item))) return true
        val genres = item.optJSONArray("genres")?.let { a -> (0 until a.length()).map { a.optInt(it) }.toSet() } ?: return false
        if (genres.isEmpty()) return false
        // « Pas ce genre-là » : on écarte ce qui partage au moins deux genres avec un titre refusé,
        // ou le même genre unique.
        return entries.values.any { e ->
            if (e.state != "nogenre") return@any false
            val g = e.genres
            val common = g.intersect(genres).size
            common >= 2 || (common == 1 && (g.size == 1 || genres.size == 1))
        }
    }

    fun myList(ctx: Context): List<JSONObject> =
        all(ctx).values.filter { it.state == "want" }.sortedByDescending { it.time }.map { it.item }

    /** Titres aimés ou qui te tentent, les plus récents d'abord (pour « Pour toi »). */
    fun seeds(ctx: Context): List<Entry> =
        all(ctx).values.filter { it.state == "liked" || it.state == "want" }
            .sortedWith(compareBy<Entry> { if (it.state == "liked") 0 else 1 }.thenByDescending { it.time })
            .take(4)

    fun counts(ctx: Context): JSONObject {
        val a = all(ctx).values
        return JSONObject().apply { STATES.forEach { s -> put(s, a.count { it.state == s }) } }
    }

    fun reset(ctx: Context) = p(ctx).edit().remove("items").apply()

    fun toJson(list: List<JSONObject>) = JSONArray().apply { list.forEach { put(it) } }
}
