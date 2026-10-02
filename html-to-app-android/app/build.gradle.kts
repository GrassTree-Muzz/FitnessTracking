import java.util.Properties

plugins {
    id("com.android.application")
}

// Kept out of git: add thunderforestKey=... to local.properties. Without it the route map shows a plain line.
val thunderforestKey = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}.getProperty("thunderforestKey", "").filter { it.isLetterOrDigit() }

android {
    namespace = "com.example.peakmildeffort"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.peakmildeffort"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.2"
        buildConfigField("String", "THUNDERFOREST_KEY", "\"$thunderforestKey\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation("androidx.activity:activity:1.13.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.core:core:1.19.1")
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.webkit:webkit:1.17.1")
}
