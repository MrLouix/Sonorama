# JNA et Vosk : les fonctions natives sont liées par réflexion, rien ne doit être renommé ni retiré.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
-keep class io.github.mrlouix.sonorama.voice.VoskNative { *; }
-dontwarn java.awt.**
