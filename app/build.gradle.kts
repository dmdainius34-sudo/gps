plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "lt.example.gnss"
    compileSdk = 34

    // Vienas pastovus raktas – nauja APK versija diegiama ant senos be išdiegimo
    signingConfigs {
        create("stable") {
            storeFile = file("gnss-debug.keystore")
            storePassword = "android"
            keyAlias = "gnssdebug"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "lt.example.gnss"
        minSdk = 26
        targetSdk = 34
        // Kiekvienas GitHub Actions paleidimas – didesnis versijos numeris
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("stable")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
}
