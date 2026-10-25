import java.util.Properties

plugins {
    id("com.android.application")
}

val keystoreProperties = Properties().apply {
    val f = rootProject.file("../keys/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.tg.ghosta"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tg.ghosta"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.3"
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
    // no runtime deps: plain AccessibilityService + ServerSocket on loopback
    testImplementation("junit:junit:4.13.2")
}
