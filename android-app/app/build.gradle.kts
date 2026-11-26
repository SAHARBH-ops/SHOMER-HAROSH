plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.shomerharosh.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.shomerharosh.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 13
        versionName = "1.1.2"
    }

    signingConfigs {
        create("release") {
            storeFile = file("${System.getProperty("user.home")}/shomer-harosh-key.jks")
            storePassword = "SAHARbh3210@"
            keyAlias = "key0"
            keyPassword = "SAHARbh3210@"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}