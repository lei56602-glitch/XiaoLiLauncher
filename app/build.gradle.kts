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
        // Keep the launcher compatible with current Android security requirements.
        targetSdk = 35
        versionCode = 3
        versionName = "0.1.2"
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        resources {
            // BouncyCastle publishes the same OSGi metadata in several JARs;
            // it is not used by Android at runtime.
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}
dependencies {
    implementation(project(":feature:quanlian"))
}
