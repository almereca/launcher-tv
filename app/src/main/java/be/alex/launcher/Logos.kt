package be.alex.launcher

import android.content.Context
import java.io.File

/** Les logos ne sont plus affichés : on supprime juste les images gardées par l'ancienne version. */
object Logos {
    fun cleanup(ctx: Context) {
        try {
            File(ctx.filesDir, "logos").deleteRecursively()
        } catch (e: Exception) {
        }
    }
}
