plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.couchtv.launcher"
    compileSdk = 34
    defaultConfig {
        applicationId = "app.couchtv.launcher"   // same id as upstream so its apply.sh still works; do not install both on one TV
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "0.0.1"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // TODO: a real keystore. Upstream falls back to the debug key, which we do not want.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false }
}
// No dependencies on purpose. Keep it that way unless the memory cost is measured.
