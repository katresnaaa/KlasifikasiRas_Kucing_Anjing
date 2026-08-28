plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.ras_kucing_anjing"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.ras_kucing_anjing"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        mlModelBinding = true
    }
}

configurations.all {
    resolutionStrategy {
        // Pastikan semua dependency org.tensorflow:tensorflow-lite memakai versi yang sama
        force("org.tensorflow:tensorflow-lite:2.17.0")
    }
}

dependencies {
    // Gunakan satu versi TensorFlow Lite yang cukup baru untuk mendukung op FULLY_CONNECTED v12
    implementation("org.tensorflow:tensorflow-lite:2.17.0")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.tensorflow.lite.support)
    implementation(libs.tensorflow.lite.metadata)

    implementation(libs.tensorflow.lite.gpu)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}