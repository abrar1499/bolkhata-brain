plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}
android {
    namespace = "com.abrar.gupshup"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.abrar.gupshup.pro"
        minSdk = 26
        targetSdk = 35
        versionCode = 23
        versionName = "6.3"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    signingConfigs {
        create("fixed") {
            storeFile = file("gupshup-debug.jks"); storePassword = "gupshup123"
            keyAlias = "gupshup"; keyPassword = "gupshup123"
        }
    }
    buildTypes { debug { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("fixed") } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { jniLibs { useLegacyPackaging = true } }
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
chaquopy {
    defaultConfig { version = "3.12" }
}
dependencies {
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.19.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
}
