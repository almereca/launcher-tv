package be.alex.launcher

import android.content.ComponentName
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService

/**
 * Surveille la lecture en cours (Netflix, YouTube, Disney+… publient ce qu'elles jouent)
 * pour éteindre la TV « à la fin du contenu » :
 *  - quand la lecture s'arrête après avoir joué ;
 *  - ou quand le titre change pendant la lecture (épisode suivant lancé automatiquement).
 *
 * Android exige pour ça un accès aux notifications, accordé par le script d'installation.
 */
class MediaWatcher : NotificationListenerService() {

    /** Ce qui joue en ce moment dans une app (pour le bouton ★ de la télécommande). */
    data class NowPlaying(val pkg: String, val title: String, val subtitle: String?, val art: Bitmap?, val artUri: String?)

    companion object {
        @Volatile
        var connected = false
            private set

        @Volatile
        var nowPlaying: NowPlaying? = null
            private set

        private var armedTitle: String? = null
        private var wasPlaying = false

        fun reset() {
            armedTitle = null
            wasPlaying = false
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val attached = mutableListOf<Pair<MediaController, MediaController.Callback>>()

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> attach(list) }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        try {
            val msm = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
            val me = ComponentName(this, MediaWatcher::class.java)
            msm.addOnActiveSessionsChangedListener(sessionsListener, me, main)
            attach(msm.getActiveSessions(me))
        } catch (e: Exception) {
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        connected = false
        detach()
    }

    private fun detach() {
        for ((c, cb) in attached) {
            try {
                c.unregisterCallback(cb)
            } catch (e: Exception) {
            }
        }
        attached.clear()
    }

    private fun attach(list: List<MediaController>?) {
        detach()
        for (c in list.orEmpty()) {
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = check(c)
                override fun onMetadataChanged(metadata: MediaMetadata?) = check(c)
                override fun onSessionDestroyed() = ended()
            }
            c.registerCallback(cb, main)
            attached += c to cb
            check(c)
        }
    }

    private fun remember(c: MediaController) {
        if (c.packageName == packageName) return
        val md = c.metadata ?: return
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        if (title.isNullOrBlank()) return
        val sub = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val art = md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val uri = md.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
        nowPlaying = NowPlaying(c.packageName, title, sub, art, uri)
    }

    private fun check(c: MediaController) {
        val st = c.playbackState?.state
        if (st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_PAUSED || st == PlaybackState.STATE_BUFFERING) remember(c)
        if (!Kids.endOfContent(this)) {
            reset()
            return
        }
        val state = c.playbackState?.state
        val title = c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        if (state == PlaybackState.STATE_PLAYING) {
            val armed = armedTitle
            if (armed == null) {
                if (!title.isNullOrBlank()) armedTitle = title
            } else if (!title.isNullOrBlank() && title != armed) {
                // Un nouvel épisode vient de démarrer tout seul.
                ended()
                return
            }
            wasPlaying = true
        } else if (wasPlaying && (state == PlaybackState.STATE_STOPPED || state == PlaybackState.STATE_NONE)) {
            ended()
        }
    }

    private fun ended() {
        if (!Kids.endOfContent(this) || !wasPlaying) return
        reset()
        Kids.sleepNow(this)
    }
}
