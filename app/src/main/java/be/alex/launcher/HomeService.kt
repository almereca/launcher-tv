package be.alex.launcher

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Service d'accessibilité du launcher :
 *  - si l'accueil d'origine s'affiche (bouton Home), on rebascule sur le launcher ;
 *  - la télécommande du téléphone s'en sert pour Retour, et en secours pour les flèches et OK.
 */
class HomeService : AccessibilityService() {

    companion object {
        @Volatile
        private var instance: HomeService? = null

        fun running(): Boolean = instance != null

        /** App affichée en ce moment (dernière fenêtre ouverte, hors clavier et système). */
        @Volatile
        var foreground: String? = null
            private set

        /** Remplace le texte du champ sélectionné (recherche d'une app). Renvoie false si impossible. */
        fun setText(text: String): Boolean {
            val s = instance ?: return false
            val root = s.rootInActiveWindow ?: return false
            val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
            if (!field.isEditable) return false
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }

        fun back(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_BACK) == true

        /** Déplace la sélection dans l'écran actuel (marche dans les apps qui exposent leurs éléments). */
        fun move(key: String): Boolean {
            val s = instance ?: return false
            val root = s.rootInActiveWindow ?: return false
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
            if (key == "ok") return focused.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val dir = when (key) {
                "up" -> View.FOCUS_UP
                "down" -> View.FOCUS_DOWN
                "left" -> View.FOCUS_LEFT
                else -> View.FOCUS_RIGHT
            }
            val next = focused.focusSearch(dir) ?: return false
            return next.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        }
    }

    /** Fenêtres système qui ne doivent pas déclencher l'écran du code (volume, clavier…). */
    private val passThrough = setOf("com.android.systemui", "android")

    private val stock = setOf(
        "com.google.android.tvlauncher",
        "com.google.android.apps.tv.launcherx",
        "com.google.android.leanbacklauncher"
    )

    /** TV rallumée : si l'espace Enfants a verrouillé la TV, on affiche l'écran du code. */
    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (Kids.locked(context)) LockActivity.show(context)
            // Les défis repartent de zéro à chaque rallumage.
            Defis.schedule(context)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Keys.init(this)
        instance = this
        try {
            registerReceiver(screenOn, IntentFilter(Intent.ACTION_SCREEN_ON))
        } catch (e: Exception) {
        }
    }

    override fun onDestroy() {
        instance = null
        try {
            unregisterReceiver(screenOn)
        } catch (e: Exception) {
        }
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in passThrough && "inputmethod" !in pkg) foreground = pkg
        // TV verrouillée : toute autre app (même lancée par une touche de la télécommande) est recouverte.
        if (Kids.locked(this) && pkg != packageName && pkg !in passThrough) {
            LockActivity.show(this)
            return
        }
        // Défi en attente : on ne peut pas y échapper en passant par l'accueil puis en relançant une app.
        if (Defis.pending && Defis.enabled(this) && pkg != packageName && pkg !in passThrough) {
            QuizActivity.show(this)
            return
        }
        if (pkg in stock) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }

    override fun onInterrupt() {}
}
