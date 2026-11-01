package be.alex.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Recherche faite depuis le téléphone : les résultats s'affichent sur le téléphone,
 * et chaque résultat peut être regardé sur la TV, épinglé en favori, ou donner sa bande-annonce,
 * sans avoir à lancer le contenu.
 */
object Finder {
    private val pool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    fun find(ctx: Context, raw: String): JSONArray {
        val q = raw.trim()
        val out = JSONArray()
        if (q.length < 2) return out
        val installed = Apps.launchables(ctx)

        val movies = pool.submit(Callable {
            try {
                Tmdb.search(q).take(8).map { r -> r to pool.submit(Callable { Tmdb.providers(r) }) }
                    .map { (r, f) -> r to (try { f.get(8, TimeUnit.SECONDS) } catch (e: Exception) { emptyList() }) }
            } catch (e: Exception) {
                emptyList()
            }
        })
        val videos = pool.submit(Callable {
            try {
                YouTube.search(q)
            } catch (e: Exception) {
                emptyList()
            }
        })

        installed.filter { it.label.contains(q, ignoreCase = true) }.take(3).forEach { a ->
            out.put(JSONObject().put("kind", "app").put("pkg", a.pkg).put("title", Apps.defaultLabel(a.pkg, installed))
                .put("subtitle", "App"))
        }
        val m = try { movies.get(15, TimeUnit.SECONDS) } catch (e: Exception) { emptyList() }
        for ((r, providers) in m) {
            val watch = providers.firstNotNullOfOrNull { name ->
                Catalog.forProvider(name)?.let { e -> Apps.resolve(e, installed)?.let { e.label } }
            }
            out.put(JSONObject().apply {
                put("kind", "movie")
                put("id", r.id); put("type", r.type); put("title", r.title)
                put("year", r.year ?: ""); put("overview", r.overview)
                put("poster", r.poster ?: ""); put("backdrop", r.backdrop ?: "")
                put("providers", JSONArray(providers))
                put("genres", JSONArray(r.genres))
                put("watch", watch ?: "")
                put("subtitle", listOfNotNull(if (r.type == "tv") "Série" else "Film", r.year, watch ?: providers.firstOrNull()).joinToString(" · "))
            })
        }
        val v = try { videos.get(15, TimeUnit.SECONDS) } catch (e: Exception) { emptyList() }
        for (r in v.sortedBy { if (it.kind == "channel") 0 else 1 }.take(9)) {
            out.put(JSONObject().apply {
                put("kind", r.kind)
                put("id", r.id); put("title", r.title); put("channel", r.channel); put("channelId", r.channelId)
                put("thumb", r.thumb ?: "")
                put("subtitle", if (r.kind == "channel") "Chaîne YouTube" else "YouTube · ${r.channel}")
            })
        }
        return out
    }

    /** Action sur un résultat : watch (regarder sur la TV), trailer, pin (favori), pinchannel (chaîne de la vidéo). */
    fun act(ctx: Context, action: String, raw: String): String {
        val o = try {
            JSONObject(raw)
        } catch (e: Exception) {
            return "Résultat illisible."
        }
        val installed = Apps.launchables(ctx)
        val yt = Apps.resolve(Catalog.YOUTUBE, installed)
        return when (o.str("kind")) {
            "app" -> {
                val pkg = o.str("pkg")
                when (action) {
                    "pin" -> {
                        Pins.add(ctx, Pin(Pins.newId(), o.str("title"), "App", null, null, pkg, null)); "Ajouté aux favoris."
                    }
                    else -> { onMain { Apps.launch(ctx, pkg) }; "Ouverture sur la TV." }
                }
            }
            "movie" -> {
                val r = Tmdb.Result(o.optInt("id"), o.str("type"), o.str("title"), o.str("year").ifEmpty { null },
                    o.str("overview"), o.str("poster").ifEmpty { null }, o.str("backdrop").ifEmpty { null })
                val providers = o.optJSONArray("providers")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
                val target = providers.firstNotNullOfOrNull { name ->
                    Catalog.forProvider(name)?.let { e -> Apps.resolve(e, installed)?.let { e to it } }
                }
                when (action) {
                    "trailer" -> {
                        val key = Tmdb.trailer(r, Prefs.trailerVO(ctx)) ?: return "Pas de bande-annonce trouvée."
                        onMain { Apps.openUri(ctx, "https://www.youtube.com/watch?v=$key", yt) }
                        "Bande-annonce lancée sur la TV."
                    }
                    "pin" -> {
                        val link = target?.let { DeepLinks.find(r, it.first, it.second) }
                        Pins.add(ctx, Pin(Pins.newId(), r.title, target?.first?.label ?: (if (r.type == "tv") "Série" else "Film"),
                            r.backdrop ?: r.poster, link, target?.second, r.title))
                        "« ${r.title} » ajouté aux favoris."
                    }
                    else -> when {
                        target != null -> {
                            val link = DeepLinks.find(r, target.first, target.second)
                            val pin = Pin("tmp", r.title, "", null, link, target.second, r.title)
                            onMain { if (link != null) Pins.open(ctx, pin) else Apps.searchIn(ctx, target.second, r.title) }
                            if (link != null) "Ouverture de « ${r.title} » dans ${target.first.label}."
                            else "Ouverture de ${target.first.label} (lien direct introuvable pour ce titre)."
                        }
                        providers.isEmpty() -> "Pas trouvé sur une plateforme en Belgique."
                        else -> "Disponible sur ${providers.first()}, mais l'app n'est pas installée."
                    }
                }
            }
            "next" -> {
                val n = WatchNext.load(ctx).firstOrNull { it.title == o.str("title") && it.pkg == o.str("pkg") }
                    ?: return "Ce contenu n'est plus dans la liste de la TV."
                when (action) {
                    "pin" -> {
                        Pins.add(ctx, Pin(Pins.newId(), n.title, Apps.labelOf(ctx, n.pkg),
                            n.image?.takeIf { it.startsWith("http") }, n.intentUri, n.pkg, null))
                        "« ${n.title} » ajouté aux favoris."
                    }
                    else -> { onMain { WatchNext.open(ctx, n) }; "Reprise sur la TV." }
                }
            }
            "video", "channel" -> {
                val r = YouTube.Result(o.str("kind"), o.str("id"), o.str("title"), o.str("channel"), o.str("channelId"), o.str("thumb").ifEmpty { null })
                when (action) {
                    "pin" -> {
                        Pins.add(ctx, Pin(Pins.newId(), r.title, if (r.kind == "channel") "Chaîne YouTube" else "YouTube · ${r.channel}",
                            r.thumb, YouTube.uri(r), yt, null))
                        "« ${r.title} » ajouté aux favoris."
                    }
                    "pinchannel" -> {
                        val ch = try { YouTube.channel(id = r.channelId) } catch (e: Exception) { null }
                            ?: YouTube.Result("channel", r.channelId, r.channel, r.channel, r.channelId, null)
                        Pins.add(ctx, Pin(Pins.newId(), ch.title, "Chaîne YouTube", ch.thumb, YouTube.uri(ch), yt, null))
                        "Chaîne « ${ch.title} » ajoutée aux favoris."
                    }
                    else -> { onMain { Apps.openUri(ctx, YouTube.uri(r), yt) }; "Ouverture sur la TV." }
                }
            }
            else -> "Résultat inconnu."
        }
    }

    private fun onMain(block: () -> Unit) {
        main.post { block() }
    }
}
