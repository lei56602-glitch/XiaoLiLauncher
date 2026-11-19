plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseStoreFile = System.getenv("ANDROID_KEYSTORE_PATH")
val releaseStorePassword = System.getenv("ANDROID_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("ANDROID_KEY_ALIAS")
val releaseKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")
val releaseSigningConfigured = listOf(
    releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword
).all { !it.isNullOrBlank() }
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
    signingConfigs {
        create("release") {
            if (releaseSigningConfigured) {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }
    buildTypes {
        getByName("release") {
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        resources {
            // Duplicate metadata from Bouncy Castle JARs is not needed at runtime.
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            pickFirsts += "META-INF/LICENSE.md"
        }
    }
}
dependencies {
    implementation(project(":feature:quanlian"))
    testImplementation("junit:junit:4.13.2")
}
