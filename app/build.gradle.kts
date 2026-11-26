plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.dhobson.lumi"
    compileSdk = 37
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "com.dhobson.lumi"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"

        ndk {
            // Galaxy Tab S9 and the Apple Silicon emulator are both arm64.
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                // Oboe's prebuilt library and the FluidSynth bundle both need the shared STL.
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    buildFeatures {
        compose = true
        prefab = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        // Stored uncompressed so the first-run copy to app storage is a plain stream copy.
        noCompress += "sf2"
    }

    packaging {
        jniLibs {
            // libfluidsynth.so is referenced by CMake and also lives in jniLibs.
            pickFirsts += "**/libfluidsynth.so"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.oboe)

    testImplementation(libs.junit)
}
