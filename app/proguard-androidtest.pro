# Test APK of the "minified" build type: the runner finds tests via
# reflection, so test code and its frameworks stay whole and readable.
-dontobfuscate
-keep class com.jdandroid.** { *; }
-keep class androidx.test.** { *; }
-keep class org.junit.** { *; }
-keep class junit.** { *; }
-keep class org.hamcrest.** { *; }
-dontwarn com.google.errorprone.annotations.**
