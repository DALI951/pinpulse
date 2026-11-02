plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val propVersionCode = (project.findProperty("versionCode") as? String)?.toIntOrNull() ?: 1
val propVersionName = (project.findProperty("versionName") as? String) ?: "0.1"

android {
    namespace = "com.pinpulse.radar"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pinpulse.radar"
        minSdk = 26
        targetSdk = 34
        versionCode = propVersionCode
        versionName = propVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("String", "APPLICATION_ID", "\"com.pinpulse.radar\"")
        buildConfigField("String", "VERSION_NAME", "\"$propVersionName\"")
    }

    signingConfigs {
        create("release") {
            storeFile = file("keystore/pinpulse.jks")
            storePassword = "pinpulse123"
            keyAlias = "pinpulse_key"
            keyPassword = "pinpulse123"
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // Use the same release signature so updates never trigger signature mismatch
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.play.services.location)
    implementation(libs.play.services.auth)
    implementation(libs.coil.compose)
    implementation(libs.gson)
    implementation(libs.okhttp)
    implementation(libs.paho.mqtt)

    debugImplementation(libs.androidx.ui.tooling)
}
