package be.alex.launcher

data class AppEntry(val label: String, val packages: List<String>)

/** Les apps de la colonne de gauche, dans l'ordre. Les absentes sont masquées. */
object Catalog {
    val NETFLIX = AppEntry("Netflix", listOf("com.netflix.ninja", "com.netflix.mediaclient"))
    val YOUTUBE = AppEntry("YouTube", listOf("com.google.android.youtube.tv", "com.google.android.youtube"))
    val DISNEY = AppEntry("Disney+", listOf("com.disney.disneyplus"))
    val PRIME = AppEntry("Prime Video", listOf("com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient"))
    val HBO = AppEntry("HBO Max", listOf("com.wbd.stream", "com.hbo.hbonow"))
    val CRUNCHYROLL = AppEntry("Crunchyroll", listOf("com.crunchyroll.crunchyroid"))
    val AUVIO = AppEntry("Auvio", listOf("be.rtbf.auvio"))
    val ARTE = AppEntry("Arte", listOf("tv.arte.plus7"))
    val PLAY_STORE = AppEntry("Play Store", listOf("com.android.vending"))

    /** Colonne de gauche par défaut, tant que rien n'a été réglé depuis le téléphone. */
    val favorites = listOf(NETFLIX, YOUTUBE, DISNEY, PRIME, HBO, CRUNCHYROLL, AUVIO, ARTE, PLAY_STORE)

    /** Nom court connu pour une app (sinon on prend le nom donné par Android). */
    fun labelFor(pkg: String): String? = favorites.firstOrNull { pkg in it.packages }?.label

    val youtubePackages = setOf(
        "com.google.android.youtube.tv",
        "com.google.android.youtube",
        "com.google.android.youtube.tvkids",
        "com.google.android.youtube.tvmusic"
    )

    /** Nom de plateforme renvoyé par TMDB vers notre catalogue. */
    fun forProvider(name: String): AppEntry? {
        val n = name.lowercase()
        return when {
            "netflix" in n -> NETFLIX
            "disney" in n -> DISNEY
            "prime" in n || "amazon" in n -> PRIME
            n == "max" || "hbo" in n -> HBO
            "crunchyroll" in n -> CRUNCHYROLL
            "auvio" in n || "rtbf" in n -> AUVIO
            "arte" in n -> ARTE
            else -> null
        }
    }

    /** Domaine d'un lien collé depuis le téléphone vers notre catalogue. */
    fun forHost(host: String): AppEntry? {
        val h = host.lowercase()
        return when {
            "netflix." in h -> NETFLIX
            "disneyplus." in h -> DISNEY
            "primevideo." in h || "amazon." in h -> PRIME
            "max.com" in h || "hbomax." in h -> HBO
            "crunchyroll." in h -> CRUNCHYROLL
            "auvio" in h || "rtbf.be" in h -> AUVIO
            "arte.tv" in h -> ARTE
            "youtube." in h || "youtu.be" in h -> YOUTUBE
            else -> null
        }
    }
}
