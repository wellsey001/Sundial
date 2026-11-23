plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.wellsey.sundial"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.wellsey.sundial"
        minSdk = 31
        targetSdk = 34
        // GitHub Actions sets the build number, so every build installs as a newer version.
        val build = (System.getenv("GITHUB_RUN_NUMBER") ?: "0").toInt()
        versionCode = 100 + build
        versionName = "1.0.$build"
    }

    signingConfigs {
        // A fixed key kept in the repo, so each new build installs over the last one.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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
