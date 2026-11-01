package be.alex.launcher

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

object Apps {
    data class Installed(val label: String, val pkg: String)

    /** Entrée « À suivre » de la colonne : se range, se renomme et se masque comme une app. */
    const val NEXT = "launcher.next"
    const val NEXT_LABEL = "À suivre"

    fun launchables(ctx: Context): List<Installed> {
        val pm = ctx.packageManager
        val found = LinkedHashMap<String, String>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (ri in pm.queryIntentActivities(intent, 0)) {
                val p = ri.activityInfo.packageName
                if (p == ctx.packageName || found.containsKey(p)) continue
                found[p] = ri.loadLabel(pm).toString()
            }
        }
        return found.map { Installed(it.value, it.key) }.sortedBy { it.label.lowercase() }
    }

    fun resolve(entry: AppEntry, installed: List<Installed>): String? =
        entry.packages.firstOrNull { p -> installed.any { it.pkg == p } }
            ?: installed.firstOrNull { it.label.equals(entry.label, ignoreCase = true) }?.pkg

    /** Réglage d'une app de la colonne de gauche : nom personnalisé (ou null) et logo affiché ou non. */
    data class MenuItem(val pkg: String, val name: String?, val logo: Boolean)

    /** App telle qu'affichée : label final, label d'origine, logo. */
    data class MenuEntry(val pkg: String, val label: String, val original: String, val logo: Boolean)

    private fun p(ctx: Context) = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    /** Configuration enregistrée, ou null tant que rien n'a été réglé depuis le téléphone. */
    fun menuConfig(ctx: Context): List<MenuItem>? {
        val raw = p(ctx).getString("menu", null)
        if (raw != null) {
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    MenuItem(o.str("pkg"), o.str("name").ifBlank { null }, o.optBoolean("logo", false))
                }.filter { it.pkg.isNotBlank() }
            } catch (e: Exception) {
                null
            }
        }
        // Ancien format (simple liste d'apps).
        return Prefs.menuApps(ctx)?.map { MenuItem(it, null, false) }
    }

    fun saveMenu(ctx: Context, items: List<MenuItem>) {
        val arr = JSONArray()
        for (m in items.distinctBy { it.pkg }) {
            arr.put(JSONObject().apply {
                put("pkg", m.pkg)
                put("name", m.name?.trim()?.take(40) ?: "")
                put("logo", m.logo)
            })
        }
        p(ctx).edit().putString("menu", arr.toString()).putBoolean("menuKnowsNext", true).apply()
    }

    fun defaultLabel(pkg: String, installed: List<Installed>): String =
        if (pkg == NEXT) NEXT_LABEL else Catalog.labelFor(pkg) ?: installed.firstOrNull { it.pkg == pkg }?.label ?: pkg

    /** Apps affichées dans la colonne de gauche, dans l'ordre choisi. */
    fun menu(ctx: Context, installed: List<Installed>): List<MenuEntry> {
        var config = menuConfig(ctx)
            ?: Catalog.favorites.mapNotNull { resolve(it, installed) }.map { MenuItem(it, null, false) }
        // Réglages enregistrés avant que « À suivre » fasse partie de la liste : on l'ajoute en tête.
        if (config.none { it.pkg == NEXT } && !p(ctx).getBoolean("menuKnowsNext", false)) {
            config = listOf(MenuItem(NEXT, null, false)) + config
        }
        return config.mapNotNull { m ->
            if (m.pkg != NEXT && installed.none { it.pkg == m.pkg }) return@mapNotNull null
            val original = defaultLabel(m.pkg, installed)
            MenuEntry(m.pkg, m.name ?: original, original, m.logo)
        }
    }

    fun icon(ctx: Context, pkg: String): Drawable? = try {
        ctx.packageManager.getApplicationIcon(pkg)
    } catch (e: Exception) {
        null
    }

    fun labelOf(ctx: Context, pkg: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    fun start(ctx: Context, intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    fun launch(ctx: Context, pkg: String): Boolean {
        val pm = ctx.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return false
        return start(ctx, intent)
    }

    /** Ouvre un lien dans l'app voulue, sinon dans n'importe quelle app, sinon lance l'app. */
    fun openUri(ctx: Context, uri: String, pkg: String?): Boolean {
        val u = Uri.parse(uri)
        if (pkg != null && start(ctx, Intent(Intent.ACTION_VIEW, u).setPackage(pkg))) return true
        if (start(ctx, Intent(Intent.ACTION_VIEW, u))) return true
        return pkg != null && launch(ctx, pkg)
    }

    /** Ouvre la recherche interne de l'app avec ce titre, sinon lance juste l'app. */
    fun searchIn(ctx: Context, pkg: String, query: String): Boolean {
        val intent = Intent(Intent.ACTION_SEARCH).setPackage(pkg).putExtra(SearchManager.QUERY, query)
        if (ctx.packageManager.queryIntentActivities(intent, 0).isNotEmpty() && start(ctx, intent)) return true
        return launch(ctx, pkg)
    }
}
