plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.spirtosan.mobilevault"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.spirtosan.mobilevault"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.2.0-m2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        }
        // Like release (not debuggable, so ART runs at full speed) but debug-signed and with the
        // crypto benchmark screen. Used to measure Argon2 timings on the real phones.
        create("bench") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            applicationIdSuffix = ".bench"
            versionNameSuffix = "-bench"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    // The benchmark screen exists only in debug and bench builds, never in release.
    sourceSets {
        getByName("debug") {
            java.srcDir("src/bench/kotlin")
            assets.srcDir("src/bench/assets")
            manifest.srcFile("src/bench/AndroidManifest.xml")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    implementation(project(":core-crypto"))

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")   // copy/eye/key icons; R8 drops the unused ones
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Backup client (Task 07). No Google Play services, Firebase or ML Kit anywhere (Huawei rule).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")   // 5.x needs compileSdk 37 + a newer AGP
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")   // BiometricPrompt needs a FragmentActivity; biometric pulls an old one
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")   // QR scanner: ZXing core, own camera code

    // Benchmark screen only: compares against the old BouncyCastle Argon2.
    debugImplementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    "benchImplementation"("org.bouncycastle:bcprov-jdk18on:1.78.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
