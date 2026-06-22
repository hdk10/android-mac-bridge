plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lattiq.androidbridge"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lattiq.androidbridge"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"

        // MUST match TOKEN in mac/bridge.py
        buildConfigField("String", "BRIDGE_TOKEN", "\"change-me-shared-secret\"")
    }

    buildFeatures {
        buildConfig = true
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
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
