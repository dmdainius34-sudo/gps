plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "lt.example.gnss"
    compileSdk = 34

    defaultConfig {
        applicationId = "lt.example.gnss"
        minSdk = 26
        targetSdk = 34
        // kiekvienas build – didesnis versijos numeris (GitHub Actions paleidimo numeris)
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = run
        versionName = "1.0.$run"
    }

    // Pastovus derinimo parašas (raktas saugykloje): naujas APK diegiamas ant seno be ištrynimo, duomenys išlieka.
    // Tai tik derinimo raktas, ne leidybos.
    signingConfigs {
        getByName("debug") {
            storeFile = file("gnss-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // Anthropic Java SDK atsineša naujesnę kotlin-stdlib – metaduomenų versijos patikra praleidžiama
        freeCompilerArgs += listOf("-Xskip-metadata-version-check")
    }

    packaging {
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.kotlin_module",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/INDEX.LIST")
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    // Claude programoje (oficialus Anthropic Java SDK)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

tasks.withType<Test>().configureEach {
    // Testų (ir savikontrolės) išvestis matoma CI žurnale
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
    }
    System.getenv("GNSS_SAMPLE_DIR")?.let { systemProperty("gnss.sample", it) }
}
