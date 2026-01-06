# sherpa-onnx
-keep class com.k2fsa.sherpa.onnx.** { *; }

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}
