import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val secrets = Properties().apply {
    val f = rootProject.file("secrets.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(key: String): String = (secrets.getProperty(key) ?: "").trim()

android {
    namespace = "be.alex.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "be.alex.launcher"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "2.0"
        buildConfigField("String", "TMDB_KEY", "\"${secret("TMDB_KEY")}\"")
        buildConfigField("String", "YOUTUBE_KEY", "\"${secret("YOUTUBE_KEY")}\"")
        buildConfigField("String", "UNSPLASH_KEY", "\"${secret("UNSPLASH_KEY")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/**", "/META-INF/*.kotlin_module")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("io.coil-kt:coil:2.7.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.zxing:core:3.5.3")
    // Client ADB : la TV se pilote elle-même (touches de la télécommande depuis le téléphone).
    implementation("dev.mobile:dadb:1.2.10")
}
