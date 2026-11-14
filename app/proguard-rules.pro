# Add project specific ProGuard rules here.
# Keep Media3 classes
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Keep Hilt generated classes
-keep class dagger.hilt.** { *; }
-dontwarn dagger.hilt.**

# Keep Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }

# Keep Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep class * extends androidx.room.Dao { <methods>; }
-dontwarn androidx.room.paging.**

# ===== Gson / Retrofit 反序列化的 model =====
-keepattributes Signature, *Annotation*
-keep class com.radio.chinese.domain.model.** { *; }
-keep class com.radio.chinese.data.remote.** { *; }
-keepclassmembers, allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ===== kotlinx.serialization (SourceAvailabilityStore JSON 解析) =====
-keepattributes RuntimeVisibleAnnotations, AnnotationDefault
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# ===== DataStore =====
-keep class androidx.datastore.** { *; }

# ===== broadcastradio 编译期 stub =====
# android.media.broadcastradio 是 @SystemApi 不在公共 SDK，HardwareRadioProbe 靠源码 stub 编译；
# 运行时依赖"类名与方法签名和 framework 完全一致"才能被 boot classpath 的真类按 parent-first 遮蔽。
# 混淆一旦重命名或删改签名，遮蔽失效，实测通道会拿到 stub 抛的异常或 ClassCastException，结论失真。
-keep class android.media.broadcastradio.** { *; }

# ===== OkHttp =====
-dontwarn okhttp3.**
-dontwarn okio.**
