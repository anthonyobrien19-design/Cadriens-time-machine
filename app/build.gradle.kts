plugins {
    id("com.android.application")
}

android {
    namespace = "com.family.timemachine"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.family.timemachine"
        minSdk = 24          // Android 7.0 and newer (covers most tablets, incl. Fire tablets from 2017 on)
        targetSdk = 34
        // Always increases (minutes since 2026-01-01), so every new build installs over the old one.
        versionCode = ((System.currentTimeMillis() / 1000 - 1767225600) / 60).toInt()
        versionName = "1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "local")
    }

    // One fixed signing key (keystore/release.jks, made by the first GitHub build and saved in the repo).
    // Android only installs an update over the old app if both were signed with the same key.
    val familyKey = rootProject.file("keystore/release.jks")
    signingConfigs {
        create("family") {
            storeFile = familyKey
            storePassword = "timemachine"
            keyAlias = "timemachine"
            keyPassword = "timemachine"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (familyKey.exists()) signingConfigs.getByName("family") else signingConfigs.getByName("debug")
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
// No libraries at all: the app uses only what is built into Android.
