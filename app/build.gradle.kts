plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val sherpaOnnxVersion = "1.12.23"
val aarFile = layout.projectDirectory.file("libs/sherpa-onnx-$sherpaOnnxVersion.aar").asFile

tasks.register("checkSherpaOnnxAar") {
    doLast {
        if (!aarFile.exists()) {
            throw GradleException(
                "sherpa-onnx AAR not found at: $aarFile\n" +
                "Run ./build-sherpa-onnx-aar.sh first to build it from source."
            )
        }
    }
}

tasks.named("preBuild") {
    dependsOn("checkSherpaOnnxAar")
}

android {
    namespace = "com.translander"
    compileSdk = 35

    defaultConfig {
        applicationId = "at.webformat.translander"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.1.0"

        // Only include arm64-v8a for modern phones (~50% smaller APK)
        // Remove this filter if you need to support older 32-bit devices or emulators
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    buildFeatures {
        compose = true
        viewBinding = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    // sherpa-onnx AAR (built from source via build-sherpa-onnx-aar.sh)
    implementation(files(aarFile))

    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // Jetpack DataStore
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // OkHttp for model download
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
