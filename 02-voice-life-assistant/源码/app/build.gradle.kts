plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cn.seniorvoice.demo"
    compileSdk = 35

    defaultConfig {
        applicationId = "cn.seniorvoice.demo"
        minSdk = 26
        targetSdk = 34
        versionCode = 21
        versionName = "0.8.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    buildTypes {
        debug { manifestPlaceholders["cleartextAllowed"] = "true" }
        release { manifestPlaceholders["cleartextAllowed"] = "false" }
    }
    packaging { resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1") }
}

dependencies {
    implementation(fileTree("libs/b-agent") { include("*.jar") })
    implementation(files("libs/vosk-android-0.3.47.aar"))
    implementation(files("libs/jna-5.13.0.aar"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
