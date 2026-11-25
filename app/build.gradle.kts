plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.github.terrydaktal.whisperkeyboard"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.terrydaktal.whisperkeyboard"
        minSdk = 29
        targetSdk = 36
        versionCode = 14
        versionName = "2.5.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.okhttp)
    testImplementation(libs.junit)
}
