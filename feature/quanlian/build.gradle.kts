plugins {
    id("com.android.library")
}

android {
    namespace = "com.xiaoli.quanlian"
    compileSdk = 35
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:transfer-android"))
    implementation("androidx.core:core:1.15.0")
}
