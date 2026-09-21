// =====================================================================
// settings.gradle.kts – PetCare project settings
// Declares the project name, included modules, and plugin repositories.
// =====================================================================

pluginManagement {
    repositories {
        // Google's Maven repository (required for Android/AndroidX/Navigation plugins)
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        // Maven Central for Kotlin and other JVM libraries
        mavenCentral()
        // Gradle Plugin Portal for community plugins
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Fail if any subproject declares its own repositories (keeps deps centralised)
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }

}

// The human-readable project name shown in Android Studio
rootProject.name = "PetCare"

// The only module in this single-module project
include(":app")
