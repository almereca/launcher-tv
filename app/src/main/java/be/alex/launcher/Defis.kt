package be.alex.launcher

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * Défis pour les enfants : à intervalle régulier pendant qu'ils regardent,
 * la TV fait une pause et pose une petite question à résoudre avec la télécommande.
 *
 *  - calculs : + et − jusqu'à 20 (6 ans), + − × ÷ jusqu'à 100 (8 ans), réponse tapée avec
 *    les chiffres de la télécommande ou le pavé à l'écran ;
 *  - grandeurs (m, cm, kg, g, heures, minutes, litres, euros) pour les grands ;
 *  - mots de néerlandais à écrire lettre par lettre, phrases à remettre dans l'ordre.
 *
 * Aucun QCM : l'enfant produit toujours sa réponse (chiffres tapés, lettres ou mots à assembler).
 */
object Defis {
    const val ACTION = "be.alex.launcher.DEFI"

    /** Un défi a été lancé et n'est pas encore réussi (ou passé par un parent). */
    @Volatile var pending = false

    val TYPES = linkedMapOf(
        "calc" to "Calculs",
        "units" to "Grandeurs",
        "nlword" to "Mots en néerlandais",
        "nlorder" to "Phrases en néerlandais",
        "frorder" to "Phrases en français"
    )

    /** Opérations proposées au niveau 8 ans. */
    val OPS = linkedMapOf("plus" to "+", "minus" to "−", "times" to "×", "divide" to "÷")

    /** Une question : soit un choix parmi des réponses, soit des mots à remettre dans l'ordre. */
    data class Question(
        val type: String,
        val prompt: String,
        val hint: String,
        val choices: List<String> = emptyList(),
        val answer: String = "",
        val words: List<String> = emptyList(),
        /** Réponse tapée avec les chiffres de la télécommande plutôt que choisie. */
        val numeric: Boolean = false,
        /** "6" ou "8" : affiché quand les niveaux sont mélangés. */
        val level: String = "",
        /** Séparateur à l'affichage : " " pour des mots, "" pour des lettres. */
        val joiner: String = " "
    )

    // ---------- Réglages ----------

    private fun p(ctx: Context) = ctx.getSharedPreferences("defis", Context.MODE_PRIVATE)

    fun enabled(ctx: Context) = p(ctx).getBoolean("on", false)
    fun interval(ctx: Context) = p(ctx).getInt("every", 30)
    fun count(ctx: Context) = p(ctx).getInt("count", 2)
    /** "6", "8" ou "mix". */
    fun level(ctx: Context) = p(ctx).getString("level", "mix") ?: "mix"
    fun types(ctx: Context): Set<String> =
        p(ctx).getString("types", null)?.split(',')?.filter { it in TYPES }?.toSet()?.ifEmpty { null }
            ?: setOf("calc", "nlword", "nlorder", "frorder")
    fun ops(ctx: Context): Set<String> =
        p(ctx).getString("ops", null)?.split(',')?.filter { it in OPS }?.toSet()?.ifEmpty { null } ?: OPS.keys

    fun save(
        ctx: Context, on: Boolean?, every: Int?, count: Int?, level: String?, types: Set<String>?,
        ops: Set<String>? = null, numeric: Boolean? = null
    ) {
        val e = p(ctx).edit()
        on?.let { e.putBoolean("on", it) }
        every?.let { e.putInt("every", it.coerceIn(1, 240)) }
        count?.let { e.putInt("count", it.coerceIn(1, 10)) }
        level?.takeIf { it in setOf("6", "8", "mix") }?.let { e.putString("level", it) }
        types?.filter { it in TYPES }?.takeIf { it.isNotEmpty() }?.let { e.putString("types", it.joinToString(",")) }
        ops?.filter { it in OPS }?.takeIf { it.isNotEmpty() }?.let { e.putString("ops", it.joinToString(",")) }
        e.apply()
        // Défis coupés : on arrête aussi celui qui serait affiché ou en attente.
        if (on == false) QuizActivity.skip()
        schedule(ctx)
    }

    fun state(ctx: Context): JSONObject = JSONObject().apply {
        put("on", enabled(ctx))
        put("every", interval(ctx))
        put("count", count(ctx))
        put("level", level(ctx))
        put("types", JSONArray(types(ctx).toList()))
        put("ops", JSONArray(ops(ctx).toList()))
        put("next", p(ctx).getLong("next", 0L).takeIf { enabled(ctx) && it > System.currentTimeMillis() } ?: JSONObject.NULL)
        put("running", QuizActivity.isShowing())
    }

    // ---------- Programmation ----------

    private fun pending(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, ACTION.hashCode(), Intent(ctx, SleepReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** Programme le prochain défi (ou annule si les défis sont coupés). */
    fun schedule(ctx: Context, inMinutes: Int? = null) {
        val app = ctx.applicationContext
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(app)
        am.cancel(pi)
        if (!enabled(app)) return
        val t = System.currentTimeMillis() + (inMinutes ?: interval(app)) * 60_000L
        p(app).edit().putLong("next", t).apply()
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
        } catch (e: SecurityException) {
            am.set(AlarmManager.RTC_WAKEUP, t, pi)
        }
    }

    /** Réveil : on ne dérange que si la TV est allumée et qu'une app (pas l'accueil) est ouverte. */
    fun onAlarm(ctx: Context) {
        val app = ctx.applicationContext
        if (!enabled(app)) return
        val screenOn = (app.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        val fg = HomeService.foreground
        val watching = screenOn && fg != null && fg != app.packageName && !Kids.locked(app)
        when {
            watching -> {
                QuizActivity.show(app)
                schedule(app)
            }
            // TV en veille : on attend le rallumage (voir HomeService).
            !screenOn -> {}
            // Sur l'accueil : on revérifie dans 5 minutes.
            else -> schedule(app, 5)
        }
    }

    // ---------- Questions ----------

    fun questions(ctx: Context): List<Question> {
        val types = types(ctx).toList()
        val lvl = level(ctx)
        val ops = ops(ctx).toList()
        return (0 until count(ctx)).map {
            val older = when (lvl) { "6" -> false; "8" -> true; else -> Random.nextBoolean() }
            val tag = if (lvl == "mix") (if (older) "8" else "6") else ""
            // Les grandeurs sont pour les grands : chez les petits, on les remplace par un calcul.
            val t = types.random().let { if (it == "units" && !older) "calc" else it }
            val q = when (t) {
                "calc" -> if (older) calcBig(ops) else calcSmall()
                "units" -> units()
                "nlword" -> nlWord(older)
                "nlorder" -> order(if (older) NL_LONG.random() else NL_SHORT.random(), dutch = true)
                else -> order(if (older) FR_LONG.random() else FR_SHORT.random(), dutch = false)
            }
            q.copy(level = tag)
        }
    }

    /** 6 ans : + et − jusqu'à 20, réponse tapée. */
    private fun calcSmall(): Question {
        val (text, result) = if (Random.nextBoolean()) {
            val a = Random.nextInt(1, 11); val b = Random.nextInt(1, 11)
            "$a + $b" to a + b
        } else {
            val a = Random.nextInt(5, 21); val b = Random.nextInt(1, a)
            "$a − $b" to a - b
        }
        return Question("calc", "$text = ?", "Compte sur tes doigts si tu veux.", answer = result.toString(), numeric = true)
    }

    /** 8 ans : + − × ÷ jusqu'à 100, réponse tapée. */
    private fun calcBig(ops: List<String>): Question {
        val (text, result) = when (ops.random()) {
            "plus" -> { val a = Random.nextInt(10, 90); val b = Random.nextInt(2, 101 - a); "$a + $b" to a + b }
            "minus" -> { val a = Random.nextInt(20, 101); val b = Random.nextInt(2, a); "$a − $b" to a - b }
            "times" -> { val a = Random.nextInt(2, 11); val b = Random.nextInt(2, 11); "$a × $b" to a * b }
            else -> { val b = Random.nextInt(2, 11); val r = Random.nextInt(2, 11); "${b * r} ÷ $b" to r }
        }
        return Question("calc", "$text = ?", "Tape ta réponse avec les chiffres de la télécommande.",
            answer = result.toString(), numeric = true)
    }

    /** Conversions de grandeurs, toujours avec un résultat entier. */
    private fun units(): Question {
        val n = Random.nextInt(1, 10)
        val (text, result) = listOf(
            "$n m = ? cm" to n * 100,
            "${n * 100} cm = ? m" to n,
            "$n kg = ? g" to n * 1000,
            "${n * 1000} g = ? kg" to n,
            "${(n % 3) + 1} h = ? min" to ((n % 3) + 1) * 60,
            "${((n % 3) + 1) * 60} min = ? h" to (n % 3) + 1,
            "${(n % 5) + 1} min = ? s" to ((n % 5) + 1) * 60,
            "$n L = ? cL" to n * 100,
            "$n € = ? centimes" to n * 100,
            "${n * 10} mm = ? cm" to n
        ).random()
        return Question("units", text, "Grandeurs : tape ta réponse avec les chiffres.", answer = result.toString(), numeric = true)
    }

    /**
     * Un mot à écrire avec les lettres mélangées : en néerlandais (l'article est donné),
     * ou l'inverse, en français. Les petits ont des mots courts.
     */
    private fun nlWord(older: Boolean): Question {
        val pool = WORDS.filter { w ->
            val n = noun(w.nl); val f = noun(w.fr)
            ' ' !in n && ' ' !in f && (older || (n.length <= 5 && f.length <= 6))
        }
        val w = pool.random()
        val toDutch = Random.nextBoolean()
        val target = if (toDutch) noun(w.nl) else noun(w.fr)
        val letters = target.map { it.toString() }
        var mixed = letters.shuffled()
        var guard = 0
        while (mixed == letters && guard++ < 10) mixed = letters.shuffled()
        val article = if (toDutch) w.nl.removeSuffix(noun(w.nl)).trim() else w.fr.removeSuffix(noun(w.fr)).trim()
        return Question(
            "nlword",
            if (toDutch) "Écris « ${w.fr} » en néerlandais" else "Écris « ${w.nl} » en français",
            "${w.emoji}   " + (if (article.isNotEmpty()) "$article …" else "…"),
            answer = letters.joinToString(" "),
            words = mixed,
            joiner = ""
        )
    }

    /** Le mot sans son article (de, het, le, la, les, l'). */
    private fun noun(s: String): String {
        for (a in listOf("de ", "het ", "le ", "la ", "les ")) if (s.startsWith(a)) return s.removePrefix(a)
        return if (s.startsWith("l'")) s.removePrefix("l'") else s
    }

    private fun order(s: Pair<String, String>, dutch: Boolean): Question {
        val words = s.first.split(' ')
        var mixed = words.shuffled()
        var guard = 0
        while (mixed == words && guard++ < 10) mixed = words.shuffled()
        return Question(
            if (dutch) "nlorder" else "frorder",
            if (dutch) "Remets la phrase en néerlandais dans l'ordre" else "Remets la phrase dans l'ordre",
            if (dutch) "En français : « ${s.second} »" else s.second,
            answer = s.first,
            words = mixed
        )
    }

    private data class Word(val nl: String, val fr: String, val emoji: String)

    private val WORDS = listOf(
        Word("de hond", "le chien", "🐶"), Word("de kat", "le chat", "🐱"), Word("het paard", "le cheval", "🐴"),
        Word("de koe", "la vache", "🐮"), Word("de vis", "le poisson", "🐟"), Word("de vogel", "l'oiseau", "🐦"),
        Word("de muis", "la souris", "🐭"), Word("de beer", "l'ours", "🐻"), Word("het konijn", "le lapin", "🐰"),
        Word("de kip", "la poule", "🐔"), Word("de eend", "le canard", "🦆"), Word("de leeuw", "le lion", "🦁"),
        Word("de olifant", "l'éléphant", "🐘"), Word("de draak", "le dragon", "🐉"),
        Word("de appel", "la pomme", "🍎"), Word("de banaan", "la banane", "🍌"), Word("de aardbei", "la fraise", "🍓"),
        Word("het brood", "le pain", "🍞"), Word("de kaas", "le fromage", "🧀"), Word("het ei", "l'œuf", "🥚"),
        Word("de melk", "le lait", "🥛"), Word("het water", "l'eau", "💧"), Word("de taart", "le gâteau", "🎂"),
        Word("de soep", "la soupe", "🍲"), Word("de aardappel", "la pomme de terre", "🥔"),
        Word("de frietjes", "les frites", "🍟"), Word("de wafel", "la gaufre", "😋"), Word("de chocolade", "le chocolat", "🍫"),
        Word("de pannenkoek", "la crêpe", "🥞"), Word("het ijsje", "la glace", "🍦"),
        Word("het huis", "la maison", "🏠"), Word("de school", "l'école", "🏫"), Word("het bed", "le lit", "🛏️"),
        Word("de tafel", "la table", "🍽️"), Word("de deur", "la porte", "🚪"), Word("het kasteel", "le château", "🏰"),
        Word("de auto", "la voiture", "🚗"), Word("de fiets", "le vélo", "🚲"), Word("de trein", "le train", "🚆"),
        Word("de tram", "le tram", "🚋"), Word("de bal", "le ballon", "⚽"), Word("het boek", "le livre", "📖"),
        Word("de schoen", "la chaussure", "👟"), Word("de paraplu", "le parapluie", "☂️"), Word("de koning", "le roi", "👑"),
        Word("de zon", "le soleil", "☀️"), Word("de maan", "la lune", "🌙"), Word("de regen", "la pluie", "🌧️"),
        Word("de sneeuw", "la neige", "❄️"), Word("de zee", "la mer", "🌊"), Word("het strand", "la plage", "🏖️"),
        Word("de boom", "l'arbre", "🌳"), Word("de bloem", "la fleur", "🌸"), Word("de kerstboom", "le sapin de Noël", "🎄"),
        Word("rood", "rouge", "🍒"), Word("blauw", "bleu", "💙"), Word("groen", "vert", "🍀"), Word("geel", "jaune", "🍋"),
        Word("zwart", "noir", "⚫"), Word("wit", "blanc", "⚪"),
        Word("twee", "deux", "2️⃣"), Word("drie", "trois", "3️⃣"), Word("vier", "quatre", "4️⃣"), Word("vijf", "cinq", "5️⃣"),
        Word("tien", "dix", "🔟"), Word("ja", "oui", "👍"), Word("nee", "non", "👎"), Word("dank je", "merci", "🙏"),
        Word("hallo", "bonjour", "👋"), Word("slaapwel", "bonne nuit", "😴")
    )

    /** Phrases courtes (6 ans) et plus longues (8 ans), avec leur traduction. */
    private val NL_SHORT = listOf(
        "Ik eet graag frietjes" to "J'aime manger des frites",
        "De zee is koud" to "La mer est froide",
        "Het regent weer" to "Il pleut encore",
        "Ik wil een wafel" to "Je veux une gaufre",
        "Mijn kat is lui" to "Mon chat est paresseux",
        "De leeuw brult" to "Le lion rugit",
        "Ik heb honger" to "J'ai faim",
        "De draak is groen" to "Le dragon est vert",
        "Dank je wel" to "Merci beaucoup",
        "De eend zwemt" to "Le canard nage"
    )
    private val NL_LONG = listOf(
        "Sinterklaas brengt snoep voor de kinderen" to "Saint-Nicolas apporte des bonbons aux enfants",
        "Aan zee eten we een ijsje" to "À la mer, on mange une glace",
        "Mijn opa fietst elke dag naar de bakker" to "Mon papy va chaque jour à vélo chez le boulanger",
        "De tram rijdt door Brussel" to "Le tram roule dans Bruxelles",
        "Na school spelen we in het park" to "Après l'école, on joue au parc",
        "Ik neem mijn paraplu want het regent" to "Je prends mon parapluie car il pleut",
        "De draak slaapt in het kasteel" to "Le dragon dort dans le château",
        "Wij kijken samen naar een film" to "Nous regardons un film ensemble"
    )
    private val FR_SHORT = listOf(
        "Le dragon mange des frites" to "Un dragon très belge",
        "Ma tortue fait du vélo" to "Une tortue sportive",
        "Le chat lit un livre" to "Un chat très savant",
        "Papa danse dans la cuisine" to "Ça se passe à la maison",
        "La vache porte des lunettes" to "Une vache bizarre",
        "Il drache sur la plage" to "La météo belge",
        "Le lion mange une gaufre" to "Un goûter royal"
    )
    private val FR_LONG = listOf(
        "Ce soir nous mangeons des patates" to "Le menu du soir",
        "Saint-Nicolas passe par la cheminée cette nuit" to "Le 6 décembre approche",
        "Le lion de Belgique fait la sieste au soleil" to "Un lion fatigué",
        "Mon frère a caché les gaufres sous son lit" to "Une cachette secrète",
        "Un pirate cherche son trésor dans la mer du Nord" to "Une chasse au trésor",
        "Septante enfants chantent dans le tram" to "Un tram très bruyant",
        "Le petit dragon a peur des chatouilles" to "Un dragon pas très courageux"
    )


    /** Petits mots d'encouragement après une bonne réponse. */
    val BRAVO = listOf("Bravo !", "Goed zo!", "Trop fort !", "Super !", "Yes ! 🎉", "Génial !", "Knap gedaan!", "Chapeau !")
    val RETRY = listOf("Presque ! Essaie encore.", "Pas tout à fait, réessaie !", "Oups, encore une fois ?", "Bijna! Encore un essai.")
}
