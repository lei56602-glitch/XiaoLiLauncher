plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.xiaoli.transfer.android"
    compileSdk = 35
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Keep the crypto provider and PKIX APIs on the same published release.
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.86")
}
