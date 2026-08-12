# Firebase / FCM
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**
-keepattributes InnerClasses, Signature

# Retrofit + Gson
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-keep,allowobfuscation,allowshrinking @interface retrofit2.http.*
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep class com.amirapay.mgmt.data.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**

# Kotlinx Coroutines
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# App model/service classes referenced via reflection/WorkManager/DirectBoot
-keep class com.amirapay.mgmt.** { *; }