// =====================================================================
// app/build.gradle.kts – PetCare app module build file
// Applies all required plugins and declares all dependencies via the
// version catalog (libs.*) to keep versions centralised.
// =====================================================================

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    // KSP – required for Room annotation processing (replaces kapt)
    alias(libs.plugins.ksp)
    // Safe-args – generates type-safe navigation argument classes
    alias(libs.plugins.androidx.navigation.safeargs)
}

android {
    // Must match the package declared in AndroidManifest.xml
    namespace = "com.petcare.app"
    compileSdk = 35          // Latest stable as of 2025

    defaultConfig {
        applicationId = "com.petcare.app"
        minSdk = 26           // API 26 = Android 8 Oreo (as required)
        targetSdk = 35
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

    // Enable Java 8+ desugaring for modern APIs (LocalDate etc.) on API 26+
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    // ── ViewBinding ───────────────────────────────────────────────────
    // Generates binding classes for every layout XML; eliminates findViewById.
    buildFeatures {
        viewBinding = true
    }

    // ── Room schema export ────────────────────────────────────────────
    // Exports the Room schema JSON to a local directory so migrations can
    // be tracked in version control (required: exportSchema = true).
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

dependencies {
    // ── Core AndroidX ────────────────────────────────────────────────
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)

    // ── Lifecycle / ViewModel ─────────────────────────────────────────
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)

    // ── UI ────────────────────────────────────────────────────────────
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)

    // ── Navigation Component ──────────────────────────────────────────
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)

    // ── Room (ORM) ────────────────────────────────────────────────────
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)              // Coroutine extensions for Room
    ksp(libs.androidx.room.compiler)                    // KSP annotation processor

    // ── WorkManager ───────────────────────────────────────────────────
    implementation(libs.androidx.work.runtime.ktx)

    // ── Coroutines ────────────────────────────────────────────────────
    implementation(libs.kotlinx.coroutines.android)

    // ── Unit Tests ────────────────────────────────────────────────────
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // ── Android Instrumented Tests ────────────────────────────────────
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)    // Room in-memory test helper
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
