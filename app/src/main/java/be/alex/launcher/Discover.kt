package be.alex.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * « À regarder » dans l'app du téléphone : des idées choisies uniquement sur les plateformes
 * installées sur la TV (catalogue belge de TMDB). Recalculé toutes les 6 heures.
 */
object Discover {
    private val pool = Executors.newFixedThreadPool(6)
    private var cached: JSONArray? = null
    private var cachedAt = 0L

    private data class Platform(val entry: AppEntry, val pkg: String, val ids: String)

    private data class Section(
        val title: String,
        val subtitle: String,
        val queries: List<Pair<String, String>>,
        /** Âge maximum accepté (rangée famille) : les titres classés au-dessus sont retirés. */
        val maxAge: Int? = null
    )

    /** Oublie les idées en cache (par exemple après l'ajout d'une clé TMDB). */
    @Synchronized
    fun reset() {
        cached = null
        cachedAt = 0L
    }

    /** « Regardé récemment » (toujours à jour) puis les idées (recalculées toutes les 6 heures). */
    fun load(ctx: Context, fresh: Boolean = false): JSONArray {
        val out = JSONArray()
        recent(ctx)?.let { out.put(it) }
        val mine = Taste.myList(ctx)
        if (mine.isNotEmpty()) {
            out.put(JSONObject().put("title", "Ma liste").put("subtitle", "Ce qui te tente").put("items", Taste.toJson(mine)))
        }
        val taste = Taste.all(ctx)
        forYou(ctx)?.let { sec -> filtered(sec, taste)?.let { out.put(it) } }
        // Les réponses aux cartes s'appliquent tout de suite, même sur les idées en cache.
        val ideas = ideas(ctx, fresh)
        for (i in 0 until ideas.length()) filtered(ideas.getJSONObject(i), taste)?.let { out.put(it) }
        return out
    }

    private fun filtered(sec: JSONObject, taste: Map<String, Taste.Entry>): JSONObject? {
        val items = sec.optJSONArray("items") ?: return null
        val keep = JSONArray()
        for (i in 0 until items.length()) {
            val it = items.getJSONObject(i)
            if (!Taste.hidden(taste, it)) keep.put(it)
        }
        if (keep.length() == 0) return null
        return JSONObject().put("title", sec.str("title")).put("subtitle", sec.str("subtitle")).put("items", keep)
    }

    private var forYouCache: JSONObject? = null
    private var forYouKey = ""
    private var forYouAt = 0L

    /** « Pour toi » : titres proches de ce que tu as aimé, disponibles sur tes apps. */
    @Synchronized
    private fun forYou(ctx: Context): JSONObject? {
        val seeds = Taste.seeds(ctx)
        if (seeds.isEmpty() || !Tmdb.enabled()) return null
        val key = seeds.joinToString(",") { it.key }
        val now = System.currentTimeMillis()
        if (key == forYouKey && now - forYouAt < 6 * 3_600_000L) return forYouCache
        val installed = Apps.launchables(ctx)
        val seen = HashSet<String>(seeds.map { it.key })
        val candidates = ArrayList<Tmdb.Result>()
        for (s in seeds) {
            val type = s.item.str("type").ifEmpty { "movie" }
            for (r in Tmdb.recommendations(type, s.item.optInt("id")).take(8)) {
                if (seen.add("${r.type}:${r.id}") && (r.poster != null || r.backdrop != null)) candidates += r
            }
        }
        val checks = candidates.take(24).map { r -> r to pool.submit(Callable<List<String>> { Tmdb.providers(r) }) }
        val items = JSONArray()
        for ((r, f) in checks) {
            val names: List<String> = try { f.get(10, TimeUnit.SECONDS) } catch (e: Exception) { emptyList() }
            val entry = names.firstNotNullOfOrNull { n -> Catalog.forProvider(n)?.takeIf { Apps.resolve(it, installed) != null } } ?: continue
            val plat = Platform(entry, Apps.resolve(entry, installed)!!, "")
            items.put(item(r, plat, Tmdb.rating(r.type, r.id)))
            if (items.length() >= 15) break
        }
        val first = seeds.first().item.str("title")
        val sec = if (items.length() == 0) null
        else JSONObject().put("title", "Pour toi").put("subtitle", "Parce que tu as aimé « $first »").put("items", items)
        forYouCache = sec
        forYouKey = key
        forYouAt = now
        return sec
    }

    @Synchronized
    private fun ideas(ctx: Context, fresh: Boolean): JSONArray {
        val now = System.currentTimeMillis()
        cached?.let { if (!fresh && now - cachedAt < 6 * 3_600_000L && it.length() > 0) return it }
        val out = build(ctx)
        cached = out
        cachedAt = now
        return out
    }

    /** Liste « Watch Next » d'Android TV : ce que tu as commencé dans les apps. */
    private fun recent(ctx: Context): JSONObject? {
        val list = WatchNext.load(ctx)
        if (list.isEmpty()) return null
        val items = JSONArray()
        list.forEachIndexed { i, n ->
            val img = n.image
            items.put(JSONObject().apply {
                put("kind", "next")
                put("idx", i)
                put("title", n.title)
                put("pkg", n.pkg)
                put("subtitle", n.subtitle)
                put("watch", Apps.labelOf(ctx, n.pkg))
                put("progress", n.progress ?: JSONObject.NULL)
                put("poster", when {
                    img == null -> ""
                    img.startsWith("http") -> img
                    else -> "/wn/$i.img"
                })
                put("wide", true)
            })
        }
        return JSONObject().put("title", "Regardé récemment").put("subtitle", "Reprendre là où tu t'es arrêté").put("items", items)
    }

    private fun platforms(ctx: Context): List<Platform> {
        val installed = Apps.launchables(ctx)
        val byEntry = LinkedHashMap<AppEntry, MutableList<Int>>()
        for ((id, name) in Tmdb.beProviders()) {
            val e = Catalog.forProvider(name) ?: continue
            if (Apps.resolve(e, installed) == null) continue
            byEntry.getOrPut(e) { mutableListOf() } += id
        }
        return byEntry.map { (e, ids) -> Platform(e, Apps.resolve(e, installed)!!, ids.distinct().joinToString("%7C")) }
    }

    private fun build(ctx: Context): JSONArray {
        val out = JSONArray()
        if (!Tmdb.enabled()) return out
        val plats = platforms(ctx)
        if (plats.isEmpty()) return out

        val today = LocalDate.now()
        val recent = today.minusDays(150)
        val tenYears = today.minusYears(8)
        // La page change chaque semaine : les idées tournent sans changer à chaque ouverture.
        val week = (today.toEpochDay() / 7 % 3 + 1).toInt()

        val sections = listOf(
            Section("Nouveautés", "Films et séries de ces derniers mois", listOf(
                "movie" to "sort_by=popularity.desc&primary_release_date.gte=$recent&primary_release_date.lte=$today&vote_count.gte=20",
                "tv" to "sort_by=popularity.desc&first_air_date.gte=$recent&first_air_date.lte=$today&vote_count.gte=10"
            )),
            Section("Films à revoir", "Des classiques bien notés", listOf(
                "movie" to "sort_by=popularity.desc&vote_average.gte=7.4&vote_count.gte=2500&primary_release_date.lte=$tenYears&page=$week"
            )),
            Section("Séries à rattraper", "Les mieux notées", listOf(
                "tv" to "sort_by=popularity.desc&vote_average.gte=7.8&vote_count.gte=400&page=$week"
            )),
            Section("Cinéma coréen et dramas", "Films et séries de Corée du Sud", listOf(
                "tv" to "sort_by=popularity.desc&with_original_language=ko&vote_count.gte=50&page=$week",
                "movie" to "sort_by=popularity.desc&with_original_language=ko&vote_count.gte=150&page=$week"
            )),
            // Enfants de 6 à 8 ans : animation et films familiaux classés tous publics (PG au plus),
            // séries « Kids » ou « Familial ».
            Section("À voir en famille", "Pour les 6 à 8 ans", maxAge = 9, queries = listOf(
                "movie" to "sort_by=popularity.desc&with_genres=10751%7C16&without_genres=27%7C53%7C80" +
                    "&certification_country=US&certification.lte=PG&vote_count.gte=150&page=$week",
                "tv" to "sort_by=popularity.desc&with_genres=10762%7C10751&without_genres=80%7C10768&vote_count.gte=30&page=$week"
            ))
        )

        val built = ArrayList<Pair<Section, List<Pair<Tmdb.Result, Platform>>>>()
        for (s in sections) {
            // Une requête par plateforme et par type, en parallèle.
            val jobPlats = ArrayList<Platform>()
            val jobs = ArrayList<Future<List<Tmdb.Result>>>()
            for (p in plats) {
                for (q in s.queries) {
                    val type: String = q.first
                    val extra: String = q.second
                    jobPlats.add(p)
                    jobs.add(pool.submit(Callable<List<Tmdb.Result>> { fetch(type, p.ids, extra) }))
                }
            }
            val lists = ArrayList<Pair<Platform, List<Tmdb.Result>>>()
            for (k in jobs.indices) {
                val got: List<Tmdb.Result> = try {
                    jobs[k].get(12, TimeUnit.SECONDS)
                } catch (e: Exception) {
                    emptyList<Tmdb.Result>()
                }
                lists.add(Pair(jobPlats[k], got))
            }
            // On alterne les plateformes pour varier, sans doublon.
            val seen = HashSet<String>()
            val items = ArrayList<Pair<Tmdb.Result, Platform>>()
            var i = 0
            while (items.size < 18 && lists.any { pair -> pair.second.size > i }) {
                for (pair in lists) {
                    val p: Platform = pair.first
                    val list: List<Tmdb.Result> = pair.second
                    val r: Tmdb.Result = list.getOrNull(i) ?: continue
                    if (r.poster == null && r.backdrop == null) continue
                    if (!seen.add("${r.type}:${r.id}")) continue
                    items.add(Pair(r, p))
                    if (items.size >= 18) break
                }
                i++
            }
            if (items.isNotEmpty()) built.add(Pair(s, items))
        }

        // Âge et contenus signalés de chaque titre, en parallèle (mis en cache).
        val ratings = HashMap<String, Future<Tmdb.Rating?>>()
        for (b in built) for (pair in b.second) {
            val r: Tmdb.Result = pair.first
            val key = "${r.type}:${r.id}"
            if (!ratings.containsKey(key)) ratings[key] = pool.submit(Callable<Tmdb.Rating?> { Tmdb.rating(r.type, r.id) })
        }
        for (b in built) {
            val s: Section = b.first
            val items = JSONArray()
            for (pair in b.second) {
                val r: Tmdb.Result = pair.first
                val rating: Tmdb.Rating? = try {
                    ratings["${r.type}:${r.id}"]?.get(10, TimeUnit.SECONDS)
                } catch (e: Exception) {
                    null
                }
                val max = s.maxAge
                if (max != null && rating != null && rating.age > max) continue
                items.put(item(r, pair.second, rating))
                if (items.length() >= 15) break
            }
            if (items.length() > 0) {
                out.put(JSONObject().put("title", s.title).put("subtitle", s.subtitle).put("items", items))
            }
        }
        return out
    }

    private fun fetch(type: String, ids: String, extra: String): List<Tmdb.Result> = try {
        val page = Regex("page=(\\d+)").find(extra)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        Tmdb.discover(type, ids, extra.replace(Regex("&?page=\\d+"), ""), page)
    } catch (e: Exception) {
        emptyList()
    }

    /** Âge et contenus signalés au format de l'app du téléphone. */
    fun ratingJson(o: JSONObject, rating: Tmdb.Rating?) {
        if (rating == null) return
        o.put("age", rating.label)
        o.put("ageNum", rating.age)
        o.put("ageCountry", rating.country)
        o.put("descriptors", JSONArray(rating.descriptors))
    }

    private fun item(r: Tmdb.Result, p: Platform, rating: Tmdb.Rating?) = JSONObject().apply {
        ratingJson(this, rating)
        put("kind", "movie")
        put("id", r.id); put("type", r.type); put("title", r.title)
        put("year", r.year ?: ""); put("overview", r.overview)
        put("poster", r.poster ?: ""); put("backdrop", r.backdrop ?: "")
        put("providers", JSONArray().put(p.entry.label))
        put("genres", JSONArray(r.genres))
        put("watch", p.entry.label)
        put("subtitle", listOfNotNull(if (r.type == "tv") "Série" else "Film", r.year, p.entry.label).joinToString(" · "))
    }
}
