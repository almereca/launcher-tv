package be.alex.launcher

import android.content.Context
import org.json.JSONObject

/**
 * Clés API (TMDB, YouTube, Unsplash) : collées depuis le téléphone et gardées sur la TV,
 * sinon celles de secrets.properties au moment de la compilation. Elles ne repartent
 * jamais en entier vers le téléphone (seulement les 4 derniers caractères).
 */
object Keys {
    private var app: Context? = null

    fun init(ctx: Context) {
        if (app == null) app = ctx.applicationContext
    }

    private fun p() = app?.getSharedPreferences("keys", Context.MODE_PRIVATE)

    private fun saved(name: String): String = p()?.getString(name, "")?.trim().orEmpty()

    val tmdb: String get() = saved("tmdb").ifEmpty { BuildConfig.TMDB_KEY }
    val youtube: String get() = saved("youtube").ifEmpty { BuildConfig.YOUTUBE_KEY }
    val unsplash: String get() = saved("unsplash").ifEmpty { BuildConfig.UNSPLASH_KEY }

    fun set(name: String, value: String) {
        p()?.edit()?.putString(name, value.trim())?.apply()
    }

    /** Vérifie une clé auprès du service avant de l'enregistrer. Renvoie null si elle marche, sinon le problème. */
    fun test(name: String, value: String): String? {
        val k = value.trim()
        if (k.length < 10) return "Cette clé paraît trop courte."
        return try {
            when (name) {
                "tmdb" -> {
                    // Accepte la clé courte (v3) comme le long jeton d'accès en lecture (v4).
                    if (k.length > 60) Net.get("https://api.themoviedb.org/3/configuration",
                        mapOf("Authorization" to "Bearer $k", "accept" to "application/json"))
                    else Net.get("https://api.themoviedb.org/3/configuration?api_key=$k")
                }
                "youtube" -> Net.get("https://www.googleapis.com/youtube/v3/search?part=snippet&maxResults=1&q=test&key=$k")
                "unsplash" -> Net.get("https://api.unsplash.com/photos/random",
                    mapOf("Authorization" to "Client-ID $k", "Accept-Version" to "v1"))
                else -> return "Service inconnu."
            }
            null
        } catch (e: Exception) {
            val m = e.message.orEmpty()
            when {
                "401" in m || "403" in m -> "La clé est refusée par le service. Vérifie que tu as copié la bonne."
                "HTTP" in m -> "Le service répond une erreur ($m)."
                else -> "La TV n'arrive pas à joindre le service. Elle est connectée à Internet ?"
            }
        }
    }

    fun state(): JSONObject = JSONObject().apply {
        for ((name, v) in listOf("tmdb" to tmdb, "youtube" to youtube, "unsplash" to unsplash)) {
            put(name, JSONObject().put("set", v.isNotBlank()).put("end", if (v.length >= 4) v.takeLast(4) else ""))
        }
    }
}
