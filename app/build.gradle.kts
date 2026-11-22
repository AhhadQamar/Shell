plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.minimalshell"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.minimalshell"
        minSdk = 23
        targetSdk = 35
        // CI sets GITHUB_RUN_NUMBER, so every build is newer than the last and installs as an in-place update.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "1.1"
    }

    // One stable signing key for every build, so a new APK updates the old one instead of conflicting with it.
    // The keystore is committed (app/shell.jks). To use a private key instead, set these environment
    // variables (e.g. from GitHub Secrets) and nothing else needs to change.
    signingConfigs {
        create("shell") {
            storeFile = file(System.getenv("SHELL_KEYSTORE_FILE") ?: "shell.jks")
            storePassword = System.getenv("SHELL_KEYSTORE_PASSWORD") ?: "shell-launcher"
            keyAlias = System.getenv("SHELL_KEY_ALIAS") ?: "shell"
            keyPassword = System.getenv("SHELL_KEY_PASSWORD") ?: "shell-launcher"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("shell") }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("shell")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
}
