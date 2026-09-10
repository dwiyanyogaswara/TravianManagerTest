plugins {
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.0.21"
}

android {
    namespace = "com.example.travianfarmassistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.travianfarmassistant"
        minSdk = 26
        targetSdk = 35
        versionCode = 40
        versionName = "4.14.9"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
