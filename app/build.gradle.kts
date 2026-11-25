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
        versionCode = 10
        versionName = "2.1.1"
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
    implementation("com.squareup.okhttp3:okhttp:4.11.0")
    testImplementation("junit:junit:4.13.2")
}
