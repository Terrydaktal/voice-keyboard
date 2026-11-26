import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) {
        file.inputStream().use(::load)
    }
}

fun localOrEnv(propKey: String, envKey: String): String =
    (localProps.getProperty(propKey) ?: System.getenv(envKey) ?: "").trim()

fun escapeBuildConfig(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")

val ramblerS3ApiKey = localOrEnv("rambler.s3.apiKey", "RAMBLER_S3_API_KEY")
val ramblerS3AndroidCert = localOrEnv("rambler.s3.androidCert", "RAMBLER_S3_ANDROID_CERT")

android {
    namespace = "io.github.terrydaktal.whisperkeyboard"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.terrydaktal.whisperkeyboard"
        minSdk = 29
        targetSdk = 36
        versionCode = 16
        versionName = "2.5.2"

        buildConfigField("String", "RAMBLER_S3_API_KEY", "\"${escapeBuildConfig(ramblerS3ApiKey)}\"")
        buildConfigField("String", "RAMBLER_S3_ANDROID_CERT", "\"${escapeBuildConfig(ramblerS3AndroidCert)}\"")
    }

    buildFeatures {
        buildConfig = true
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
