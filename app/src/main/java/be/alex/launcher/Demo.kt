package be.alex.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.Calendar

/**
 * Mode démo pour les captures d'écran : un faux agenda et une note à la place des vrais,
 * sans rien modifier aux données enregistrées. S'arrête au redémarrage du launcher.
 */
object Demo {
    @Volatile var on = false

    /** Crédit de la photo Unsplash utilisée pendant les captures (vide sinon). */
    @Volatile var credit = ""

    fun photo(ctx: Context) = File(ctx.filesDir, "demo-wallpaper.jpg")

    /** La photo de démo remplace le fond d'écran (sans toucher au vrai) tant que le mode démo est actif. */
    fun usingPhoto(ctx: Context) = on && credit.isNotBlank() && photo(ctx).let { it.exists() && it.length() > 0 }

    /** Télécharge une photo Unsplash pour les captures, puis prévient l'écran. */
    fun prepare(ctx: Context, query: String, done: () -> Unit) {
        val app = ctx.applicationContext
        Thread {
            try {
                val r = Unsplash.search(query).optJSONObject(0)
                if (r != null) {
                    photo(app).writeBytes(Net.bytes(r.str("full").replace("w=2560", "w=1920"), 20_000))
                    credit = "Photo : ${r.str("author").take(60)} · Unsplash"
                    Unsplash.trackDownload(r.str("download"))
                }
            } catch (e: Exception) {
                credit = ""
            }
            Handler(Looper.getMainLooper()).post { done() }
        }.start()
    }

    fun events(): List<Agenda.Event> {
        fun at(dayOffset: Int, h: Int, m: Int): Long = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, dayOffset)
            set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0)
        }.timeInMillis
        return listOf(
            Agenda.Event("Soupe au potiron et patates rôties", at(0, 19, 0), false, "Famille"),
            Agenda.Event("Marché de la Grand-Place", at(1, 10, 0), false, "Famille"),
            Agenda.Event("Balade en forêt", at(2, 14, 0), false, "Famille"),
            Agenda.Event("Cours de JJB chez Vanguard", at(3, 19, 30), false, "Perso"),
            Agenda.Event("Enregistrement du podcast", at(4, 20, 30), false, "Blog")
        )
    }

    fun notes(): List<Notes.Note> = listOf(
        Notes.Note("demo", "Ce soir c'est soupe ET patates. On ne choisit pas. 🥔", true)
    )
}
