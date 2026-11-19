plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.xiaoli.launcher"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.xiaoli.launcher"
        minSdk = 23
        targetSdk = 28
        versionCode = 2
        versionName = "0.1.1"
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
