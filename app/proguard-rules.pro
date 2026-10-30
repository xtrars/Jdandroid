# R8 shrinks and optimizes the release build. The rules cover only what is
# reached via JNI or reflection. No obfuscation and kept line numbers, so crash
# reports stay readable (inlined frames: mapping.txt of the release).
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# 7-Zip-JBinding calls Java classes and methods from native code by name,
# including our callback implementations (RarOpenCallback, ISequentialOutStream
# lambda). Without these rules R8 removes them as unused.
-keep class net.sf.sevenzipjbinding.** { *; }
-keepclassmembers class net.sf.sevenzipjbinding.** { *; }
-keep class * implements net.sf.sevenzipjbinding.** { *; }
-keep class com.jdandroid.engine.Extractor$RarOpenCallback { *; }
-keep class com.jdandroid.engine.Extractor$RarExtractCallback { *; }

# NFS export: Netty 3 and the NFS client look up classes and loggers
# reflectively; no device test covers this path, so both stay whole.
-keep class org.jboss.netty.** { *; }
-keep class com.emc.ecs.nfsclient.** { *; }
# Optional Netty integrations the NFS client never uses
-dontwarn com.google.protobuf.**
-dontwarn javax.servlet.**
-dontwarn org.apache.log4j.**
-dontwarn org.apache.tomcat.jni.**
-dontwarn org.eclipse.jetty.npn.**
-dontwarn org.jboss.logging.**
-dontwarn org.jboss.marshalling.**
-dontwarn sun.security.**
# SLF4J 1.7 looks for an optional binding and falls back to no logging
-dontwarn org.slf4j.impl.StaticLoggerBinder

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
