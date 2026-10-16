plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.localvoiceime"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.localvoiceime"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }
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
