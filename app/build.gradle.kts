plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.bydbattery"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.bydbattery"
        minSdk = 23
        targetSdk = 34
        versionCode = 7
        versionName = "4.3"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
