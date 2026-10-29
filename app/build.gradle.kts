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
        versionCode = 8
        versionName = "4.4"
    }

    signingConfigs {
        getByName("debug") {
            System.getenv("ANDROID_DEBUG_KEYSTORE")?.let { path ->
                storeFile = file(path)
                storeType = "PKCS12"
                storePassword = "android"
                keyAlias = "AndroidDebugKey"
                keyPassword = "android"
            }
        }
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
