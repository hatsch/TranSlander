# Disable obfuscation (keep optimization)
-dontobfuscate

# sherpa-onnx
-keep class com.k2fsa.sherpa.onnx.** { *; }

# OkHttp
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# Kotlin Coroutines
-keepclassmembers class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep services (referenced in AndroidManifest)
-keep class com.translander.service.TextInjectionService { *; }
-keep class com.translander.service.FloatingMicService { *; }
-keep class com.translander.transcribe.AudioMonitorService { *; }

# Keep broadcast receivers
-keep class com.translander.receiver.BootReceiver { *; }

# Keep activities launched via intent
-keep class com.translander.transcribe.TranscribeActivity { *; }
