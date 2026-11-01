package be.alex.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Ce qui s'affiche sur l'accueil de la TV (réglable depuis le téléphone). */
object HomeConfig {
    /** Éléments de l'accueil, avec leur nom affiché sur le téléphone et leur valeur par défaut. */
    val widgets = linkedMapOf(
        "clock" to ("Heure" to true),
        "weather" to ("Météo" to true),
        "agenda" to ("Agenda et notes" to true),
        "search" to ("Bouton Rechercher" to true),
        "allapps" to ("Bouton Toutes les apps" to true),
        "settings" to ("Bouton Réglages" to true),
        "phone" to ("Bouton Connecter un téléphone" to false),
        "credit" to ("Crédit de la photo" to true)
    )

    private fun p(ctx: Context) = ctx.getSharedPreferences("home", Context.MODE_PRIVATE)

    fun shown(ctx: Context, key: String): Boolean = p(ctx).getBoolean(key, widgets[key]?.second ?: true)

    fun set(ctx: Context, key: String, on: Boolean) {
        if (key in widgets) p(ctx).edit().putBoolean(key, on).apply()
    }

    /** Période affichée dans l'agenda : 1 (aujourd'hui), 3 ou 7 jours. */
    fun agendaDays(ctx: Context): Int = p(ctx).getInt("agendaDays", 1)

    fun setAgendaDays(ctx: Context, days: Int) {
        if (days in setOf(1, 3, 7)) p(ctx).edit().putInt("agendaDays", days).apply()
    }

    fun state(ctx: Context): JSONArray = JSONArray().apply {
        widgets.forEach { (k, v) -> put(JSONObject().put("id", k).put("name", v.first).put("on", shown(ctx, k))) }
    }
}

/** Petites notes écrites sur le téléphone et affichées sur la TV. */
object Notes {
    data class Note(val id: String, val text: String, val shown: Boolean)

    private fun p(ctx: Context) = ctx.getSharedPreferences("home", Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Note> = try {
        val arr = JSONArray(p(ctx).getString("notes", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Note(o.str("id"), o.str("text"), o.optBoolean("shown", true))
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun save(ctx: Context, notes: List<Note>) {
        val arr = JSONArray()
        notes.take(20).forEach { n -> arr.put(JSONObject().put("id", n.id).put("text", n.text.take(280)).put("shown", n.shown)) }
        p(ctx).edit().putString("notes", arr.toString()).apply()
    }

    fun shown(ctx: Context): List<Note> = all(ctx).filter { it.shown && it.text.isNotBlank() }
}

/**
 * Agendas au format iCal (Google Agenda : « Adresse secrète au format iCal » ;
 * iCloud : calendrier partagé public). Plusieurs agendas, chacun affichable ou non.
 */
object Agenda {
    data class Source(val id: String, val name: String, val url: String, val shown: Boolean)
    data class Event(val title: String, val start: Long, val allDay: Boolean, val source: String)

    private fun p(ctx: Context) = ctx.getSharedPreferences("home", Context.MODE_PRIVATE)

    fun sources(ctx: Context): List<Source> = try {
        val arr = JSONArray(p(ctx).getString("calendars", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Source(o.str("id"), o.str("name"), o.str("url"), o.optBoolean("shown", true))
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun saveSources(ctx: Context, list: List<Source>) {
        val arr = JSONArray()
        list.take(10).forEach { s ->
            arr.put(JSONObject().put("id", s.id).put("name", s.name.take(40)).put("url", s.url).put("shown", s.shown))
        }
        p(ctx).edit().putString("calendars", arr.toString()).apply()
        synchronized(cache) { cache.clear() }
    }

    private val cache = HashMap<String, Pair<Long, List<Event>>>()

    /** Événements des prochains jours, tous agendas affichés confondus (réseau : à appeler hors fil principal). */
    fun upcoming(ctx: Context, days: Int = 1, max: Int = 5): List<Event> {
        val now = System.currentTimeMillis()
        val dayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val end = dayStart + days.coerceIn(1, 7) * 86_400_000L
        val fetchEnd = dayStart + 8 * 86_400_000L
        val all = mutableListOf<Event>()
        for (s in sources(ctx).filter { it.shown }) {
            val cached = synchronized(cache) { cache[s.id] }
            val events = if (cached != null && now - cached.first < 15 * 60_000L) cached.second else {
                val fresh = try {
                    parse(Net.get(s.url.replaceFirst("webcal://", "https://"), timeoutMs = 10_000), s.name, dayStart, fetchEnd)
                } catch (e: Exception) {
                    cached?.second ?: emptyList()
                }
                synchronized(cache) { cache[s.id] = now to fresh }
                fresh
            }
            all += events
        }
        return all.filter { it.start >= dayStart && it.start < end && (it.allDay || it.start > now - 30 * 60_000L) }
            .sortedWith(compareBy({ it.start }, { !it.allDay }))
            .take(max)
    }

    /** « 18:30 » aujourd'hui, « Demain 9:00 », « Jeu. 14:00 », « Journée ». */
    fun whenText(e: Event): String {
        val today = Calendar.getInstance()
        val c = Calendar.getInstance().apply { timeInMillis = e.start }
        val diff = dayIndex(c) - dayIndex(today)
        val time = if (e.allDay) "Journée" else SimpleDateFormat("HH:mm", Locale.FRANCE).format(c.time)
        return when (diff) {
            0 -> time
            1 -> "Demain $time"
            else -> SimpleDateFormat("EEE", Locale.FRANCE).format(c.time).replaceFirstChar { it.uppercase() } + " " + time
        }
    }

    private fun dayIndex(c: Calendar): Int = c.get(Calendar.YEAR) * 400 + c.get(Calendar.DAY_OF_YEAR)

    // ---------- Lecture d'un fichier iCal (VEVENT, avec répétitions simples) ----------

    private fun parse(text: String, source: String, from: Long, to: Long): List<Event> {
        val lines = text.replace("\r\n", "\n").replace("\n ", "").replace("\n\t", "").split('\n')
        val out = mutableListOf<Event>()
        var inEvent = false
        var props = HashMap<String, Pair<String, String>>() // nom -> (paramètres, valeur)
        val exdates = mutableListOf<Long>()
        for (line in lines) {
            when {
                line.startsWith("BEGIN:VEVENT") -> { inEvent = true; props = HashMap(); exdates.clear() }
                line.startsWith("END:VEVENT") -> {
                    inEvent = false
                    expand(props, exdates, source, from, to, out)
                }
                inEvent -> {
                    val colon = line.indexOf(':')
                    if (colon <= 0) continue
                    val head = line.substring(0, colon)
                    val value = line.substring(colon + 1)
                    val name = head.substringBefore(';').uppercase()
                    val params = if (';' in head) head.substringAfter(';') else ""
                    if (name == "EXDATE") {
                        value.split(',').mapNotNull { date(it, params)?.first }.let { exdates += it }
                    } else if (name !in props) {
                        props[name] = params to value
                    }
                }
            }
        }
        return out
    }

    private fun expand(
        props: Map<String, Pair<String, String>>, exdates: List<Long>, source: String,
        from: Long, to: Long, out: MutableList<Event>
    ) {
        if (props["STATUS"]?.second?.uppercase() == "CANCELLED") return
        val (startParams, startValue) = props["DTSTART"] ?: return
        val (start, allDay) = date(startValue, startParams) ?: return
        val title = unescape(props["SUMMARY"]?.second ?: "(Sans titre)")
        val rule = props["RRULE"]?.second
        if (rule == null) {
            if (start in from until to) out += Event(title, start, allDay, source)
            return
        }
        val r = rule.split(';').associate { it.substringBefore('=').uppercase() to it.substringAfter('=') }
        val freq = r["FREQ"] ?: return
        val interval = r["INTERVAL"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val count = r["COUNT"]?.toIntOrNull()
        val until = r["UNTIL"]?.let { date(it, "")?.first }
        val days = r["BYDAY"]?.split(',')?.mapNotNull { dayOfWeek(it.takeLast(2)) }?.toSet()

        val c = Calendar.getInstance().apply { timeInMillis = start }
        // Avance d'un coup près d'aujourd'hui pour les répétitions anciennes (sans nombre limité d'occurrences).
        if (count == null && start < from) {
            val gapDays = ((from - start) / 86_400_000L).toInt()
            when (freq) {
                "DAILY" -> c.add(Calendar.DAY_OF_YEAR, (gapDays / interval) * interval)
                "WEEKLY" -> c.add(Calendar.WEEK_OF_YEAR, (gapDays / 7 / interval) * interval)
                "MONTHLY" -> c.add(Calendar.MONTH, (gapDays / 31 / interval) * interval)
                "YEARLY" -> c.add(Calendar.YEAR, (gapDays / 366 / interval) * interval)
            }
        }
        var n = 0
        var guard = 0
        while (guard++ < 5000) {
            val t = c.timeInMillis
            if (until != null && t > until) break
            if (t >= to) break
            val dayOk = days == null || freq != "WEEKLY" || c.get(Calendar.DAY_OF_WEEK) in days
            if (dayOk) {
                n++
                if (count != null && n > count) break
                if (t >= from && t !in exdates) out += Event(title, t, allDay, source)
            }
            when {
                freq == "DAILY" -> c.add(Calendar.DAY_OF_YEAR, interval)
                freq == "WEEKLY" && days != null -> {
                    // Jour par jour dans la semaine, puis saut de (interval - 1) semaines le dimanche soir.
                    c.add(Calendar.DAY_OF_YEAR, 1)
                    if (c.get(Calendar.DAY_OF_WEEK) == c.firstDayOfWeek && interval > 1) c.add(Calendar.WEEK_OF_YEAR, interval - 1)
                }
                freq == "WEEKLY" -> c.add(Calendar.WEEK_OF_YEAR, interval)
                freq == "MONTHLY" -> c.add(Calendar.MONTH, interval)
                freq == "YEARLY" -> c.add(Calendar.YEAR, interval)
                else -> break
            }
        }
    }

    /** Date iCal vers (millisecondes, journée entière). */
    private fun date(value: String, params: String): Pair<Long, Boolean>? = try {
        val v = value.trim()
        val tzid = Regex("TZID=([^;:]+)").find(params)?.groupValues?.get(1)
        when {
            v.length == 8 || params.contains("VALUE=DATE") && !v.contains('T') -> {
                val f = SimpleDateFormat("yyyyMMdd", Locale.ROOT)
                f.parse(v.take(8))!!.time to true
            }
            v.endsWith("Z") -> {
                val f = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
                f.parse(v.dropLast(1))!!.time to false
            }
            else -> {
                val f = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.ROOT)
                if (tzid != null) f.timeZone = TimeZone.getTimeZone(tzid)
                f.parse(v.take(15))!!.time to false
            }
        }
    } catch (e: Exception) {
        null
    }

    private fun dayOfWeek(code: String): Int? = when (code.uppercase()) {
        "MO" -> Calendar.MONDAY
        "TU" -> Calendar.TUESDAY
        "WE" -> Calendar.WEDNESDAY
        "TH" -> Calendar.THURSDAY
        "FR" -> Calendar.FRIDAY
        "SA" -> Calendar.SATURDAY
        "SU" -> Calendar.SUNDAY
        else -> null
    }

    private fun unescape(s: String) = s.replace("\\n", " ").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\").trim()
}
