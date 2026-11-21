# Preserve JNI bridge class and native method signatures called from libopenstream_srt.so.
-keepclasseswithmembernames,includedescriptorclasses class dev.openstream.app.stream.SrtNativeBridge {
    native <methods>;
}

# Preserve java.util.function.Predicate.test(Object) invoked via JNI from openstream_srt.cpp.
-keep class java.util.function.Predicate {
    public boolean test(java.lang.Object);
}
