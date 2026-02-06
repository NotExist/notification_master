# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep Room entities
-keep class com.notificationmaster.data.db.entity.** { *; }

# Keep model classes for JSON serialization
-keep class com.notificationmaster.data.model.** { *; }
