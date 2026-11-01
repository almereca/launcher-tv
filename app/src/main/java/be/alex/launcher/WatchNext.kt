package be.alex.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.tv.TvContract

/** Lit la liste système « Watch Next » d'Android TV (reprendre, épisode suivant). */
object WatchNext {
    const val PERM = "com.android.providers.tv.permission.READ_EPG_DATA"

    data class Item(
        val title: String,
        val subtitle: String,
        val image: String?,
        val intentUri: String?,
        val pkg: String,
        val progress: Float?
    )

    fun load(ctx: Context): List<Item> {
        if (ctx.checkSelfPermission(PERM) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val rows = mutableListOf<Pair<Long, Item>>()
        try {
            ctx.contentResolver.query(TvContract.WatchNextPrograms.CONTENT_URI, null, null, null, null)?.use { c ->
                fun s(col: String): String? {
                    val i = c.getColumnIndex(col)
                    return if (i >= 0 && !c.isNull(i)) c.getString(i) else null
                }
                fun l(col: String): Long {
                    val i = c.getColumnIndex(col)
                    return if (i >= 0 && !c.isNull(i)) c.getLong(i) else 0L
                }
                while (c.moveToNext()) {
                    val pkg = s("package_name") ?: continue
                    // YouTube : uniquement ce qui est épinglé à la main.
                    if (pkg in Catalog.youtubePackages) continue
                    val title = s("title") ?: continue
                    val season = s("season_display_number")
                    val ep = s("episode_display_number")
                    val type = l("watch_next_type") // 0 reprendre, 1 suivant, 2 nouveau, 3 liste
                    val epText = when {
                        ep != null && season != null -> "S$season É$ep"
                        ep != null -> "É$ep"
                        else -> null
                    }
                    val verb = if (type == 1L || type == 2L) "Épisode suivant" else "Reprendre"
                    val action = if (epText != null) "$verb : $epText" else verb
                    val subtitle = "${Apps.labelOf(ctx, pkg)} · $action"
                    val dur = l("duration_millis")
                    val pos = l("last_playback_position_millis")
                    val progress = if (dur > 0 && pos > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else null
                    val image = s("poster_art_uri") ?: s("thumbnail_uri")
                    rows += l("last_engagement_time_utc_millis") to Item(title, subtitle, image, s("intent_uri"), pkg, progress)
                }
            }
        } catch (e: Exception) {
        }
        return rows.sortedByDescending { it.first }.map { it.second }.distinctBy { it.pkg + "|" + it.title }.take(8)
    }

    fun open(ctx: Context, item: Item) {
        val uri = item.intentUri
        if (uri != null) {
            try {
                if (Apps.start(ctx, Intent.parseUri(uri, Intent.URI_INTENT_SCHEME))) return
            } catch (e: Exception) {
            }
        }
        Apps.launch(ctx, item.pkg)
    }
}
