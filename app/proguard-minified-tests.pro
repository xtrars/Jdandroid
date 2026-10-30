# Only for the "minified" build type the instrumented tests run against: the
# code the tests call themselves, which the release build may shrink away.
# JNI and reflection rules stay those of proguard-rules.pro.
-keep class com.jdandroid.engine.Extractor { public *; }
-keep interface com.jdandroid.engine.Extractor$ProgressListener { *; }
-keep class com.jdandroid.data.** { *; }
-keep class com.jdandroid.engine.MediaStoreTarget { *; }
-keep class com.jdandroid.engine.Placed { *; }
-keep class com.jdandroid.engine.StorageTarget { *; }
-keep class com.jdandroid.engine.StorageTarget$Companion { *; }
-keep class androidx.room.** { *; }
-keep class androidx.sqlite.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
# Shared with the test runner (androidx.test), which calls the app's copies
-keep class androidx.tracing.** { *; }
-keep class androidx.concurrent.futures.** { *; }
-keep class com.google.common.util.concurrent.** { *; }
-keep class androidx.arch.core.** { *; }
