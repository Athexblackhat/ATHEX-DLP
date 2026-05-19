# Keep ATHEX classes
-keep class com.athex.dlp.** { *; }
-keepclassmembers class com.athex.dlp.** { *; }

# Keep network classes
-keepclassmembers class * implements java.io.Serializable { *; }
-keep class java.net.** { *; }

# Keep crypto
-keep class javax.crypto.** { *; }
-keep class java.security.** { *; }

# Keep JSON
-keep class org.json.** { *; }
-dontwarn org.json.**

# Keep Android services
-keep class * extends android.app.Service { *; }
-keep class * extends android.content.BroadcastReceiver { *; }
-keep class * extends android.service.notification.NotificationListenerService { *; }

# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}