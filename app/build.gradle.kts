plugins {
    id("com.android.application") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "2.0.21"
}

android {
    namespace = "dev.clawdpad.host"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.clawdpad.host"
        minSdk = 29           // Pixel 4 era onward; both of Rod's Pixels fine
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }
    buildTypes {
        debug { }             // v0.1 ships as a debug sideload
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
