# NewPipe Extractor proguard rules
-keep class org.schabi.newpipe.extractor.** { *; }
-dontwarn org.schabi.newpipe.extractor.**

# Media3
-keep class androidx.media3.** { *; }

# Retrofit & Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.spotifyclone.app.model.** { *; }
-keep class com.spotifyclone.app.api.** { *; }

# Room
-keep class androidx.room.** { *; }
