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
        versionCode = 15
        versionName = "0.3.3"
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

// ---------------------------------------------------------------------------
// Bundled sidekick (ghosta) APK asset
// ---------------------------------------------------------------------------
// src/main/assets/ghosta.apk is the ghosta sidekick RELEASE APK, bundled so
// ghostd can self-provision the sidekick after setup (and re-provision it on
// ghostd updates / boot) without a server push — see SidekickInstaller
// .provisionBundled. It is a build artifact of ../client-sidekick but is
// COMMITTED here on purpose: a fresh checkout must build a self-provisioning
// ghostd APK (~660 KB, keeps total APK growth well under 1.5 MB).
//
// REFRESH after rebuilding ghosta (from the ghostctl root):
//   gradle -p client-main refreshSidekickAsset
// which copies client-sidekick/app/build/outputs/apk/release/app-release.apk
// over the bundled asset. Remember to rebuild ghostd afterwards so the new
// asset is packaged in.
// ---------------------------------------------------------------------------

val sidekickAsset = file("src/main/assets/ghosta.apk")

tasks.register<Copy>("refreshSidekickAsset") {
    group = "build"
    description = "Refresh the bundled ghosta sidekick APK from client-sidekick."
    from(rootProject.file("../client-sidekick/app/build/outputs/apk/release/app-release.apk"))
    into(sidekickAsset.parentFile)
    rename { "ghosta.apk" }
}

// mergeReleaseAssets reads the assets dir — run refreshSidekickAsset as a
// SEPARATE gradle invocation before assembleRelease (same-invocation runs
// trip Gradle's implicit-dependency check on the assets dir).
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    mustRunAfter("refreshSidekickAsset")
}

// Fail the build early when the bundled asset is missing or empty (fresh
// checkout damage, bad refresh) — the self-provision flow needs it.
tasks.register("checkSidekickAsset") {
    group = "verification"
    description = "Verify the bundled ghosta sidekick APK asset is present."
    doLast {
        if (!sidekickAsset.isFile || sidekickAsset.length() == 0L) {
            throw GradleException(
                "bundled sidekick asset missing/empty: $sidekickAsset — run: gradle refreshSidekickAsset"
            )
        }
    }
}

tasks.named("preBuild") { dependsOn("checkSidekickAsset") }
