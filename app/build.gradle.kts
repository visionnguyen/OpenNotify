plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.ghien.opennotify"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.ghien.opennotify"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // Quét mã QR ghép đôi mapchat, chạy hoàn toàn trên máy (không cần Google Play Services).
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
