# mixts ProGuard Rules

# ===== 保持 Hilt 相关类 =====
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ComponentSupplier { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# ===== 保持 NanoHTTPD =====
-keep class org.nanohttpd.** { *; }
-keepclassmembers class org.nanohttpd.** { *; }

# ===== 保持 ZXing =====
-keep class com.google.zxing.** { *; }

# ===== 保持 Kotlin Serialization =====
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.mixts.android.**$$serializer { *; }
-keepclassmembers class com.mixts.android.** {
    *** Companion;
}
-keepclasseswithmembers class com.mixts.android.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ===== 保持 DataStore =====
-keep class androidx.datastore.** { *; }

# ===== 保持 Compose =====
-keep class androidx.compose.** { *; }

# ===== 保持 model 类 =====
-keep class com.mixts.android.model.** { *; }
