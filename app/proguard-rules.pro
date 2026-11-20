# Readable crash traces (the R8 mapping file is published with each release).
-keepattributes SourceFile,LineNumberTable

# JNI: libtrueform.so registers its methods on this class by name (RegisterNatives).
-keep class dev.trueform.player.audio.Native { *; }

# Classes the Cast framework and the system create by name from the manifest.
-keep class dev.trueform.player.cast.TfCastOptionsProvider { *; }
