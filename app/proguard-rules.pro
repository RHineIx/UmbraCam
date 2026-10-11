# R8/ProGuard rules

# Suppress compiler warnings for Xposed annotation packages
-dontwarn io.github.libxposed.**
# Automatically rewrite the entries in java_init.list if the classes are obfuscated
-adaptresourcefilecontents META-INF/xposed/java_init.list

# Keep only Xposed entry points and hook classes whose methods are discovered by reflection.
-keep,allowoptimization class umbra.cam.xposed.UmbraCamModule {
    public <init>();
    *;
}
-keep,allowoptimization class umbra.cam.hooks.** { *; }

-keep class io.github.libxposed.api.** { *; }
-keep interface io.github.libxposed.api.** { *; }
-keep class io.github.libxposed.service.** { *; }
-keep interface io.github.libxposed.service.** { *; }

# API 102 requirement: Keep the entry classes and their parameterless constructors intact
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

-keepattributes *Annotation*,Signature,EnclosingMethod,InnerClasses,SourceFile,LineNumberTable
