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
        targetSdk = 34
        versionCode = 7
        versionName = "0.9-configurable-min-gap"
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
