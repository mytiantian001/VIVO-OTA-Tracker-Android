-dontwarn org.slf4j.helpers.SubstituteLogger

# Protobuf
-keep class chromeos_update_engine.** { *; }
-keep class com.google.protobuf.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { *; }

# Native JNI
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class io.github.long36708.updater.** { *; }

# Vivo SecKey SDK (libvivoseckey.so JNI engine)
# 必须原样保留，否则 R8 混淆/裁剪后 SDKCipherNative.init() 失败，
# UI 会一直卡在"正在初始化加密引擎"。
-keep class com.vivo.seckeysdk.** { *; }
-keep class com.vivo.seckeysdk.utils.** { *; }
-dontwarn com.vivo.seckeysdk.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**

# Apache Commons Compress
-dontwarn org.apache.commons.compress.**
-keep class org.apache.commons.compress.** { *; }

# Kotlin Coroutines
-dontwarn kotlinx.coroutines.**

# Miuix
-keep class top.yukonga.miuix.** { *; }
