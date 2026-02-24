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

# Keep archive data classes (used by JSON parsing)
-keep class com.notificationmaster.export.archive.ArchiveData { *; }
-keep class com.notificationmaster.export.archive.ExportInfo { *; }
-keep class com.notificationmaster.export.archive.ArchiveEnvironment { *; }
-keep class com.notificationmaster.export.archive.ArchiveRange { *; }

# Keep NotificationListenerService (system binding)
-keep class com.notificationmaster.service.NotificationCaptureService { *; }

# Keep EventType enum (used in JSON serialization via valueOf)
-keep enum com.notificationmaster.data.db.entity.EventType { *; }

# Keep FilterCategory enum (used in SharedPreferences key)
-keep enum com.notificationmaster.core.filter.FilterCategory { *; }

# Keep PersistenceType constants (stored as strings in DB)
-keep class com.notificationmaster.data.db.entity.PersistenceType { *; }
