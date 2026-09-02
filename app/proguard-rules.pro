# R8: Shrinking und Optimierung sind aktiv, Umbenennen (Obfuskation) nicht.
# Klassennamen bleiben lesbar, damit Absturzberichte (Einstellungen ->
# "Letzter Absturz") direkt nachvollziehbar sind.
-dontobfuscate

# 7-Zip-JBinding ruft Java-Klassen und -Methoden aus nativem Code per Name auf.
-keep class net.sf.sevenzipjbinding.** { *; }
-keepclassmembers class net.sf.sevenzipjbinding.** { *; }

# NanoHTTPD (Click'n'Load-Server)
-keep class fi.iki.elonen.** { *; }

# Room-Entities werden per Reflection instanziiert
-keep class com.jdandroid.data.** { *; }

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
