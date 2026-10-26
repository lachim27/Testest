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
        versionCode = 1000 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1)
        versionName = "0.24-menu-dom-fix"
    }
    val signingFile = System.getenv("ZLAP_KEYSTORE")
    if (!signingFile.isNullOrBlank()) {
        signingConfigs {
            create("persistentRelease") {
                storeFile = file(signingFile)
                storePassword = System.getenv("ZLAP_KEY_PASSWORD")
                keyAlias = System.getenv("ZLAP_KEY_ALIAS")
                keyPassword = System.getenv("ZLAP_KEY_PASSWORD")
                storeType = "PKCS12"
            }
        }
        buildTypes.getByName("release") {
            signingConfig = signingConfigs.getByName("persistentRelease")
        }
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
