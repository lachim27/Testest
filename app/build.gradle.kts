plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "pl.zlapegzamin.test"
    compileSdk = 35
    defaultConfig {
        applicationId = "pl.zlapegzamin.test"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.3-monitor"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.webkit:webkit:1.14.0")
}
