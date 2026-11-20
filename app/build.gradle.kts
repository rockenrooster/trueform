plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The GitHub release's build number (the release workflow sets TF_BUILD_NUMBER to the commit count; the updater
// compares it with the latest release's tag build-<n>). Local builds have 0 and never ask GitHub.
val buildNumber = providers.environmentVariable("TF_BUILD_NUMBER").orNull?.toIntOrNull() ?: 0
val buildCommit = providers.environmentVariable("TF_COMMIT").orNull ?: ""
// Release signing (docs/RELEASE_PIPELINE.md): the keystore TF_KEYSTORE names; without it, the debug key.
val releaseKeystore = providers.environmentVariable("TF_KEYSTORE").orNull

android {
    namespace = "dev.trueform.player"
    // Compose 1.13 and core 1.19 need compileSdk 37.1+. Only the compile SDK: targetSdk stays 36 (API 37 would make
    // the cast HTTP server need the ACCESS_LOCAL_NETWORK runtime permission; docs/plan/07-chromecast.md T10).
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "dev.trueform.player"
        minSdk = 29
        targetSdk = 36
        versionCode = if (buildNumber > 0) buildNumber else 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
        buildConfigField("int", "BUILD_NUMBER", "$buildNumber")
        buildConfigField("String", "COMMIT", "\"$buildCommit\"")
        buildConfigField("String", "REPO", "\"rockenrooster/trueform\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.environmentVariable("TF_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("TF_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("TF_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    androidResources {
        noCompress += listOf("flac", "mp3", "m4a", "ogg", "opus", "wma", "wv", "wav", "aiff")
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.process)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.guava)
    implementation(libs.appcompat)
    implementation(libs.palette)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.cast)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)   // Android's org.json is a stub in JVM tests
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
