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
        versionCode = 14
        versionName = "0.16-native-query-capture"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    implementation("androidx.webkit:webkit:1.14.0")
}
