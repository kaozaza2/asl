plugins {
    alias(libs.plugins.android.library)
}

// Vendored from termux-app (Apache 2.0, see NOTICE).
android {
    namespace = "com.termux.emulator"
    compileSdk = 36
    defaultConfig { minSdk = 35 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = false }
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.0")

}
