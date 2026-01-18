# Disable obfuscation (keep optimization)
-dontobfuscate

# sherpa-onnx
-keep class com.k2fsa.sherpa.onnx.** { *; }

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep accessibility service
-keep class com.voicekeyboard.service.TextInjectionService { *; }

# Keep other services
-keep class com.voicekeyboard.service.FloatingMicService { *; }
-keep class com.voicekeyboard.receiver.BootReceiver { *; }
