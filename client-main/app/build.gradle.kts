import java.util.Properties

plugins {
    id("com.android.application")
}

val keystoreProperties = Properties().apply {
    val f = rootProject.file("../keys/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.tg.ghostd"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tg.ghostd"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        create("release") {
            if (keystoreProperties.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // nv-websocket-client: single zero-dependency jar, works on plain JVM
    // (no AndroidX/okio baggage) — smallest solid option for this app.
    implementation("com.neovisionaries:nv-websocket-client:2.14")

    // reference org.json for JVM unit tests (app itself uses android's)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
