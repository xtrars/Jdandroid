# R8 is off in the release build (isMinifyEnabled = false in app/build.gradle.kts);
# these rules only apply once the shrinker is enabled again. They cover only what
# is reached via JNI/reflection. No obfuscation, so crash reports stay readable.
-dontobfuscate

# 7-Zip-JBinding calls Java classes and methods from native code by name,
# including our callback implementations (RarOpenCallback, ISequentialOutStream
# lambda). Without these rules R8 removes them as unused.
-keep class net.sf.sevenzipjbinding.** { *; }
-keepclassmembers class net.sf.sevenzipjbinding.** { *; }
-keep class * implements net.sf.sevenzipjbinding.** { *; }
-keep class com.jdandroid.engine.Extractor$RarOpenCallback { *; }
-keep class com.jdandroid.engine.Extractor$RarExtractCallback { *; }

# Room entities/DAOs need no keep rules: Room generates code and ships its own
# consumer rules.

# Optional commons-compress formats the app does not use
-dontwarn org.brotli.dec.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.osgi.**
-dontwarn org.apache.commons.compress.compressors.brotli.**
-dontwarn org.apache.commons.compress.compressors.zstandard.**
-dontwarn org.apache.commons.compress.harmony.**
-dontwarn javax.annotation.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
