# proguard-rules.pro – ProGuard/R8 rules for PetCare.
# These rules are only applied when minifyEnabled = true (release builds).
# Currently minifyEnabled is false in debug; this file is a placeholder.

# Keep Room entity classes so R8 does not strip fields used by reflection
-keep class com.petcare.app.data.db.** { *; }

# Keep WorkManager worker classes
-keep class com.petcare.app.reminder.** { *; }
