package be.alex.launcher

import android.net.Uri
import android.text.Html

object YouTube {
    /** kind = "video" ou "channel". Pour une vidéo, channelId est la chaîne qui l'a publiée. */
    data class Result(
        val kind: String,
        val id: String,
        val title: String,
        val channel: String,
        val channelId: String,
        val thumb: String?
    )

    private val key get() = Keys.youtube
    private val cache = HashMap<String, List<Result>>()

    fun enabled(): Boolean = key.isNotBlank()

    /** Une seule requête (100 unités de quota) qui ramène vidéos et chaînes, mise en cache. */
    fun search(q: String): List<Result> {
        if (!enabled()) return emptyList()
        val k = q.lowercase()
        synchronized(cache) { cache[k]?.let { return it } }
        val j = Net.json(
            "https://www.googleapis.com/youtube/v3/search?part=snippet&maxResults=12&type=video,channel" +
                "&relevanceLanguage=fr&regionCode=BE&q=${Net.enc(q)}&key=$key"
        )
        val items = j.getJSONArray("items")
        val out = mutableListOf<Result>()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val idObj = item.getJSONObject("id")
            val sn = item.getJSONObject("snippet")
            val isChannel = idObj.str("kind").endsWith("channel")
            val id = if (isChannel) idObj.str("channelId") else idObj.str("videoId")
            if (id.isEmpty()) continue
            out += Result(
                kind = if (isChannel) "channel" else "video",
                id = id,
                title = decode(sn.str("title")),
                channel = decode(sn.str("channelTitle")),
                channelId = sn.str("channelId"),
                thumb = thumb(sn)
            )
        }
        synchronized(cache) { cache[k] = out }
        return out
    }

    /** Détails d'une chaîne (1 unité). handle = "@pseudo" si on n'a pas l'identifiant. */
    fun channel(id: String? = null, handle: String? = null): Result? {
        if (!enabled()) return null
        val filter = when {
            id != null -> "id=${Net.enc(id)}"
            handle != null -> "forHandle=${Net.enc(handle)}"
            else -> return null
        }
        val j = Net.json("https://www.googleapis.com/youtube/v3/channels?part=snippet&$filter&key=$key")
        val item = j.optJSONArray("items")?.optJSONObject(0) ?: return null
        val sn = item.getJSONObject("snippet")
        val cid = item.str("id")
        return Result("channel", cid, decode(sn.str("title")), decode(sn.str("title")), cid, thumb(sn))
    }

    /** Détails d'une vidéo (1 unité). */
    fun video(id: String): Result? {
        if (!enabled()) return null
        val j = Net.json("https://www.googleapis.com/youtube/v3/videos?part=snippet&id=${Net.enc(id)}&key=$key")
        val item = j.optJSONArray("items")?.optJSONObject(0) ?: return null
        val sn = item.getJSONObject("snippet")
        return Result("video", id, decode(sn.str("title")), decode(sn.str("channelTitle")), sn.str("channelId"), thumb(sn))
    }

    /** Lit un lien YouTube collé depuis le téléphone. */
    fun fromLink(link: String): Result? {
        val u = Uri.parse(link)
        val host = u.host?.lowercase() ?: return null
        val segs = u.pathSegments
        return when {
            "youtu.be" in host -> segs.firstOrNull()?.let { video(it) }
            segs.firstOrNull() == "watch" -> u.getQueryParameter("v")?.let { video(it) }
            segs.firstOrNull() == "shorts" || segs.firstOrNull() == "live" -> segs.getOrNull(1)?.let { video(it) }
            segs.firstOrNull() == "channel" -> segs.getOrNull(1)?.let { channel(id = it) }
            segs.firstOrNull()?.startsWith("@") == true -> channel(handle = segs.first())
            else -> null
        }
    }

    fun uri(r: Result): String =
        if (r.kind == "channel") "https://www.youtube.com/channel/${r.id}"
        else "https://www.youtube.com/watch?v=${r.id}"

    private fun thumb(sn: org.json.JSONObject): String? {
        val t = sn.optJSONObject("thumbnails") ?: return null
        for (size in listOf("high", "medium", "default")) {
            val u = t.optJSONObject(size)?.str("url")
            if (!u.isNullOrEmpty()) return u
        }
        return null
    }

    private fun decode(s: String): String = Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString()
}
