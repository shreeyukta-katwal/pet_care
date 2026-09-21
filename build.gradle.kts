// =====================================================================
// build.gradle.kts (root) – PetCare root build file
// Only declares plugins used by submodules; does NOT apply them here.
// =====================================================================

plugins {
    // Declare but do NOT apply – each module applies what it needs
    alias(libs.plugins.android.application)       apply false
    alias(libs.plugins.kotlin.android)            apply false
    alias(libs.plugins.ksp)                       apply false
    alias(libs.plugins.androidx.navigation.safeargs) apply false
}
