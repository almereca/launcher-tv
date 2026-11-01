package be.alex.launcher

/** Films et séries via TMDB, avec les plateformes disponibles en Belgique. */
object Tmdb {
    data class Result(
        val id: Int,
        val type: String,
        val title: String,
        val year: String?,
        val overview: String,
        val poster: String?,
        val backdrop: String?,
        val genres: List<Int> = emptyList()
    )

    private val key get() = Keys.tmdb

    // Accepte la clé API v3 (courte) comme le jeton v4 (long).
    private fun url(path: String): String =
        if (key.length > 60) "https://api.themoviedb.org/3$path"
        else "https://api.themoviedb.org/3$path${if ('?' in path) "&" else "?"}api_key=$key"

    private fun headers(): Map<String, String> =
        if (key.length > 60) mapOf("Authorization" to "Bearer $key", "accept" to "application/json") else emptyMap()

    fun enabled(): Boolean = key.isNotBlank()

    fun search(q: String): List<Result> {
        if (!enabled()) return emptyList()
        val j = Net.json(url("/search/multi?language=fr-BE&include_adult=false&query=${Net.enc(q)}"), headers())
        val arr = j.getJSONArray("results")
        val out = mutableListOf<Result>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val type = o.str("media_type")
            if (type != "movie" && type != "tv") continue
            out += parse(o, type)
            if (out.size >= 8) break
        }
        return out
    }

    private fun parse(o: org.json.JSONObject, type: String): Result {
        val title = if (type == "movie") o.str("title") else o.str("name")
        val date = if (type == "movie") o.str("release_date") else o.str("first_air_date")
        return Result(
            id = o.getInt("id"),
            type = type,
            title = title,
            year = date.take(4).ifEmpty { null },
            overview = o.str("overview"),
            poster = o.str("poster_path").ifEmpty { null }?.let { "https://image.tmdb.org/t/p/w342$it" },
            backdrop = o.str("backdrop_path").ifEmpty { null }?.let { "https://image.tmdb.org/t/p/w780$it" },
            genres = o.optJSONArray("genre_ids")?.let { a -> (0 until a.length()).map { a.optInt(it) } } ?: emptyList()
        )
    }

    /** Titres proches d'un film ou d'une série (recommandations TMDB). */
    fun recommendations(type: String, id: Int): List<Result> {
        if (!enabled()) return emptyList()
        return try {
            val arr = Net.json(url("/$type/$id/recommendations?language=fr-BE"), headers()).getJSONArray("results")
            (0 until arr.length()).map { parse(arr.getJSONObject(it), type) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Identifiant Wikidata (Q…) d'un film ou d'une série, qui mène aux identifiants Netflix, Disney+, etc. */
    fun wikidataId(r: Result): String? = try {
        Net.json(url("/${r.type}/${r.id}/external_ids"), headers()).str("wikidata_id").ifEmpty { null }
    } catch (e: Exception) {
        null
    }

    /** Plateformes d'abonnement disponibles en Belgique : identifiant TMDB vers nom. */
    fun beProviders(): Map<Int, String> {
        val out = LinkedHashMap<Int, String>()
        for (type in listOf("movie", "tv")) {
            try {
                val arr = Net.json(url("/watch/providers/$type?watch_region=BE&language=fr-BE"), headers()).getJSONArray("results")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    out[o.getInt("provider_id")] = o.str("provider_name")
                }
            } catch (e: Exception) {
            }
        }
        return out
    }

    /** Découverte TMDB (films ou séries) limitée à certaines plateformes en Belgique. */
    fun discover(type: String, providerIds: String, extra: String, page: Int = 1): List<Result> {
        if (!enabled()) return emptyList()
        val j = Net.json(url("/discover/$type?language=fr-BE&watch_region=BE&with_watch_providers=$providerIds" +
            "&with_watch_monetization_types=flatrate%7Cfree%7Cads&include_adult=false&page=$page&$extra"), headers())
        val arr = j.getJSONArray("results")
        return (0 until arr.length()).map { parse(arr.getJSONObject(it), type) }
    }

    /** Âge conseillé et contenus signalés (violence, langage…), d'après les classements officiels. */
    data class Rating(val label: String, val age: Int, val country: String, val descriptors: List<String>)

    private val ratingCache = HashMap<String, Rating?>()

    /** Pays consultés dans l'ordre : la Belgique d'abord, puis les voisins, puis les États-Unis. */
    private val ratingCountries = listOf("BE", "FR", "NL", "LU", "DE", "GB", "US")

    fun rating(type: String, id: Int): Rating? {
        if (!enabled()) return null
        val key = "$type:$id"
        synchronized(ratingCache) { if (ratingCache.containsKey(key)) return ratingCache[key] }
        val found = HashMap<String, Pair<String, List<String>>>()
        try {
            if (type == "tv") {
                val arr = Net.json(url("/tv/$id/content_ratings"), headers()).getJSONArray("results")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val cert = o.str("rating").trim()
                    if (cert.isNotEmpty()) found[o.str("iso_3166_1")] = cert to strings(o.optJSONArray("descriptors"))
                }
            } else {
                val arr = Net.json(url("/movie/$id/release_dates"), headers()).getJSONArray("results")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val dates = o.optJSONArray("release_dates") ?: continue
                    for (k in 0 until dates.length()) {
                        val d = dates.getJSONObject(k)
                        val cert = d.str("certification").trim()
                        if (cert.isNotEmpty()) {
                            found[o.str("iso_3166_1")] = cert to strings(d.optJSONArray("descriptors"))
                            break
                        }
                    }
                }
            }
        } catch (e: Exception) {
            return null
        }
        var out: Rating? = null
        for (c in ratingCountries) {
            val (cert, desc) = found[c] ?: continue
            val age = ageOf(c, cert) ?: continue
            // Les contenus signalés manquent souvent : on les prend dans un autre pays si besoin.
            val descriptors = (desc + ratingCountries.flatMap { found[it]?.second ?: emptyList() })
                .mapNotNull { descriptorFr(it) }.distinct()
            out = Rating(if (age == 0) "Tous publics" else "$age+", age, c, descriptors)
            break
        }
        synchronized(ratingCache) { ratingCache[key] = out }
        return out
    }

    private fun strings(a: org.json.JSONArray?): List<String> =
        if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }

    /** Classement d'un pays vers un âge (0 = tous publics). */
    private fun ageOf(country: String, raw: String): Int? {
        val c = raw.uppercase().replace(" ", "")
        if (c in setOf("AL", "ALL", "U", "TP", "G", "TV-Y", "TV-G", "0", "KT")) return 0
        // Ancien classement belge : « enfants non admis ».
        if (c == "KNT" || c == "ENA") return 16
        when (country) {
            "US" -> return when (c) {
                "PG", "TV-Y7", "TV-PG" -> 7
                "PG-13" -> 13
                "TV-14" -> 14
                "R", "TV-MA" -> 17
                "NC-17" -> 18
                else -> null
            }
            "GB" -> return when (c) {
                "PG" -> 8
                "12A", "12" -> 12
                "15" -> 15
                "18", "R18" -> 18
                else -> null
            }
        }
        return Regex("(\\d{1,2})").find(c)?.value?.toIntOrNull()?.takeIf { it in 1..18 }
    }

    /** Contenus signalés, quelle que soit la langue du classement, vers un libellé court en français. */
    private fun descriptorFr(raw: String): String? {
        val d = raw.lowercase()
        return when {
            listOf("violen", "geweld", "gewalt").any { it in d } -> "Violence"
            listOf("angst", "fear", "frighten", "horror", "horreur", "peur", "scary", "threat").any { it in d } -> "Peur"
            listOf("sex", "seks", "nud", "naakt").any { it in d } -> "Sexe"
            listOf("drug", "drogue", "alcohol", "alcool", "tabac", "smoking").any { it in d } -> "Drogues"
            listOf("language", "langage", "grof", "taal", "swear", "coarse", "profan").any { it in d } -> "Langage"
            "discrimin" in d -> "Discrimination"
            else -> null
        }
    }

    fun providers(r: Result): List<String> = try {
        val j = Net.json(url("/${r.type}/${r.id}/watch/providers"), headers())
        val be = j.getJSONObject("results").optJSONObject("BE")
        val names = mutableListOf<String>()
        if (be != null) {
            for (k in listOf("flatrate", "free", "ads")) {
                val a = be.optJSONArray(k) ?: continue
                for (i in 0 until a.length()) names += a.getJSONObject(i).str("provider_name")
            }
        }
        names.filter { it.isNotBlank() }.distinct()
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * Clé YouTube de la bande-annonce. En VO (par défaut) : langue originale du titre, puis anglais,
     * puis français. Sinon : français d'abord.
     */
    fun trailer(r: Result, vo: Boolean = true): String? {
        if (!enabled()) return null
        val orig = try {
            Net.json(url("/${r.type}/${r.id}"), headers()).str("original_language").ifEmpty { "en" }
        } catch (e: Exception) {
            "en"
        }
        val order = (if (vo) listOf(orig, "en", "fr") else listOf("fr", orig, "en")).distinct()
        val arr = try {
            Net.json(url("/${r.type}/${r.id}/videos?include_video_language=${order.joinToString(",")},null"), headers()).getJSONArray("results")
        } catch (e: Exception) {
            return null
        }
        val vids = (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { it.str("site") == "YouTube" && it.str("key").isNotEmpty() && it.str("type") in setOf("Trailer", "Teaser") }
        if (vids.isEmpty()) return null
        fun rank(v: org.json.JSONObject): Int {
            val lang = order.indexOf(v.str("iso_639_1")).let { if (it < 0) order.size else it }
            val type = if (v.str("type") == "Trailer") 0 else 1
            val official = if (v.optBoolean("official", false)) 0 else 1
            return lang * 10 + type * 2 + official
        }
        return vids.minByOrNull { rank(it) }?.str("key")
    }

}
