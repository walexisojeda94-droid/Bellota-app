# WebRTC includes JNI/native entry points and runtime-loaded implementation classes.
# Keep its public Java surface intact while allowing R8 to shrink Bellota's own code.
-keep class org.webrtc.** { *; }

# Preserve native method names used by JNI bindings.
-keepclasseswithmembernames class * {
    native <methods>;
}

# Retain metadata used by annotations, generic signatures and nested classes.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
