# AVF classes are reached by reflection and live on the bootclasspath.
-dontwarn android.system.virtualmachine.**
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
-keep,includedescriptorclasses class dev.asl.**$$serializer { *; }
-keepclassmembers class dev.asl.** { *** Companion; }
-keepclasseswithmembers class dev.asl.** { kotlinx.serialization.KSerializer serializer(...); }
