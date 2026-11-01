package be.alex.launcher

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Calendar

/**
 * Espace Enfants : extinction programmée de la TV et code de déverrouillage au rallumage.
 *
 *  - dans X minutes, ou à une heure précise (une fois ou tous les jours) ;
 *  - à la fin du contenu en cours (voir MediaWatcher) ;
 *  - si le code est activé, la TV demande le code à 4 chiffres quand on la rallume.
 */
object Kids {
    const val ACTION_WARN = "be.alex.launcher.SLEEP_WARN"
    const val ACTION_SLEEP = "be.alex.launcher.SLEEP"
    private const val WARN_BEFORE = 60_000L

    private fun p(ctx: Context) = ctx.getSharedPreferences("kids", Context.MODE_PRIVATE)

    /** Extinction ponctuelle (heure exacte en millisecondes), 0 si aucune. */
    fun sleepAt(ctx: Context): Long = p(ctx).getLong("at", 0L).let { if (it > System.currentTimeMillis()) it else 0L }

    /** Extinction quotidienne "HH:MM", vide si aucune. */
    fun daily(ctx: Context): String = p(ctx).getString("daily", "") ?: ""

    fun endOfContent(ctx: Context): Boolean = p(ctx).getBoolean("end", false)
    fun lockEnabled(ctx: Context): Boolean = p(ctx).getBoolean("lock", false)
    fun locked(ctx: Context): Boolean = p(ctx).getBoolean("locked", false)

    fun code(ctx: Context): String = p(ctx).getString("code", null) ?: newCode(ctx)

    fun newCode(ctx: Context): String {
        val c = (0 until 4).map { SecureRandom().nextInt(10) }.joinToString("")
        p(ctx).edit().putString("code", c).apply()
        return c
    }

    fun sleepIn(ctx: Context, minutes: Int) {
        p(ctx).edit().putLong("at", System.currentTimeMillis() + minutes.coerceIn(1, 600) * 60_000L).apply()
        schedule(ctx)
    }

    /** hhmm = "21:30". Une fois (prochaine occurrence) ou tous les jours. */
    fun sleepAtTime(ctx: Context, hhmm: String, everyDay: Boolean) {
        val next = nextOccurrence(hhmm) ?: return
        if (everyDay) p(ctx).edit().putString("daily", hhmm).apply()
        else p(ctx).edit().putLong("at", next).apply()
        schedule(ctx)
    }

    fun setEndOfContent(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean("end", on).apply()
        MediaWatcher.reset()
    }

    fun cancelTimers(ctx: Context) {
        p(ctx).edit().putLong("at", 0L).apply()
        schedule(ctx)
    }

    fun cancelDaily(ctx: Context) {
        p(ctx).edit().putString("daily", "").apply()
        schedule(ctx)
    }

    fun setLock(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean("lock", on).apply()
        if (on) code(ctx)
        if (!on) unlock(ctx)
    }

    fun lockNow(ctx: Context) {
        p(ctx).edit().putBoolean("locked", true).apply()
        LockActivity.show(ctx)
    }

    fun unlock(ctx: Context) {
        p(ctx).edit().putBoolean("locked", false).apply()
        LockActivity.dismiss()
    }

    /** Prochaine extinction prévue (ponctuelle ou quotidienne), ou null. */
    fun nextSleep(ctx: Context): Long? {
        val a = sleepAt(ctx).takeIf { it > 0 }
        val d = daily(ctx).takeIf { it.isNotBlank() }?.let { nextOccurrence(it) }
        return listOfNotNull(a, d).minOrNull()
    }

    private fun nextOccurrence(hhmm: String): Long? {
        val parts = hhmm.split(':')
        val h = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val m = parts.getOrNull(1)?.toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= System.currentTimeMillis() + 5_000) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    private fun pending(ctx: Context, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, action.hashCode(),
            Intent(ctx, SleepReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** Programme le réveil système pour la prochaine extinction (et l'avertissement 1 minute avant). */
    fun schedule(ctx: Context) {
        val app = ctx.applicationContext
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val sleep = pending(app, ACTION_SLEEP)
        val warn = pending(app, ACTION_WARN)
        am.cancel(sleep)
        am.cancel(warn)
        val t = nextSleep(app) ?: return
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, sleep)
            if (t - WARN_BEFORE > System.currentTimeMillis()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t - WARN_BEFORE, warn)
            }
        } catch (e: SecurityException) {
            am.set(AlarmManager.RTC_WAKEUP, t, sleep)
        }
    }

    /** Éteint la TV maintenant (et verrouille si le code est activé). */
    fun sleepNow(ctx: Context) {
        val app = ctx.applicationContext
        val e = p(app).edit()
        if (p(app).getLong("at", 0L) <= System.currentTimeMillis() + 5_000) e.putLong("at", 0L)
        e.putBoolean("end", false)
        if (lockEnabled(app)) e.putBoolean("locked", true)
        e.apply()
        MediaWatcher.reset()
        schedule(app)
        Remote.sleepTv(app)
    }

    fun state(ctx: Context): JSONObject = JSONObject().apply {
        put("next", nextSleep(ctx) ?: JSONObject.NULL)
        put("at", sleepAt(ctx).takeIf { it > 0 } ?: JSONObject.NULL)
        put("daily", daily(ctx))
        put("end", endOfContent(ctx))
        put("endAvailable", MediaWatcher.connected)
        put("lock", lockEnabled(ctx))
        put("code", if (lockEnabled(ctx)) code(ctx) else "")
        put("locked", locked(ctx))
    }
}

/** Réveils de l'espace Enfants, et reprogrammation après un redémarrage de la TV. */
class SleepReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Kids.ACTION_WARN -> Toast.makeText(context, "La télé s'éteint dans 1 minute", Toast.LENGTH_LONG).show()
            Kids.ACTION_SLEEP -> Kids.sleepNow(context)
            Defis.ACTION -> Defis.onAlarm(context)
            Intent.ACTION_BOOT_COMPLETED -> {
                Kids.schedule(context)
                Defis.schedule(context)
                if (Kids.locked(context)) LockActivity.show(context)
            }
        }
    }
}
