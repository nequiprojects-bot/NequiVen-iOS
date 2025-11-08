-keep,allowobfuscation class com.ios.nequixofficialv2.security.** {
    <methods>;
}

-keepclassmembers,allowobfuscation class com.ios.nequixofficialv2.security.** {
    private <methods>;
    private <fields>;
}

-optimizations method/inlining/*,code/merging/*,code/simplification/advanced

-keepattributes !LocalVariable*,!SourceDebugExtension,!SourceDebugExtension,!LocalVariableTable,!LocalVariableTypeTable

-assumenosideeffects class android.util.Log {
    public static *** *(...);
}

-assumenosideeffects class java.io.PrintStream {
    public void *(...);
}

-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }

-dontwarn javax.crypto.**
-dontwarn java.security.**
-dontwarn java.lang.**
