plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.localvoiceime"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.localvoiceime"
        minSdk = 26
        targetSdk = 36
        versionCode = 21
        versionName = "1.4.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    val persistentKeystore = System.getenv("LOCALVOICE_KEYSTORE_PATH")
    if (!persistentKeystore.isNullOrBlank()) {
        signingConfigs.create("persistent") {
            storeFile = file(persistentKeystore)
            storePassword = System.getenv("LOCALVOICE_STORE_PASSWORD")
            keyAlias = System.getenv("LOCALVOICE_KEY_ALIAS")
            keyPassword = System.getenv("LOCALVOICE_KEY_PASSWORD")
        }
        buildTypes.getByName("debug").signingConfig = signingConfigs.getByName("persistent")
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    androidResources {
        noCompress += "onnx"
    }
}

dependencies {
    implementation("com.github.k2-fsa:sherpa-onnx:1.13.4")
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
