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
        versionCode = 1
        versionName = "1.0"
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
    implementation("com.anthropic:anthropic-java:3.3.0")
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
