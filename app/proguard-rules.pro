# Hinweis: R8 ist im Release-Build derzeit AUS (isMinifyEnabled = false in
# app/build.gradle.kts). Diese Regeln greifen erst, wenn der Shrinker wieder
# eingeschaltet wird. Sie sind bewusst schmal gehalten: nur das, was per
# JNI/Reflection aufgerufen wird und dem Shrinker daher unsichtbar bleibt.
# Umbenennen (Obfuskation) bleibt aus, damit Absturzberichte (Einstellungen ->
# "Letzter Absturz") direkt nachvollziehbar sind.
-dontobfuscate

# 7-Zip-JBinding ruft Java-Klassen und -Methoden aus nativem Code per Name auf -
# auch UNSERE Implementierungen der Callback-Interfaces (RarOpenCallback,
# ISequentialOutStream-Lambda). Ohne diese Regeln entfernt R8 sie als "unbenutzt".
-keep class net.sf.sevenzipjbinding.** { *; }
-keepclassmembers class net.sf.sevenzipjbinding.** { *; }
-keep class * implements net.sf.sevenzipjbinding.** { *; }
-keep class com.jdandroid.engine.Extractor$RarOpenCallback { *; }

# Room-Entities/DAOs und NanoHTTPD brauchen keine Keep-Regeln: Room generiert
# Code statt Reflection zu nutzen und bringt eigene Consumer-Regeln mit,
# NanoHTTPD wird nur aus Kotlin-Code heraus aufgerufen.

# Optionale Kompressionsformate von commons-compress, die die App nicht nutzt
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
