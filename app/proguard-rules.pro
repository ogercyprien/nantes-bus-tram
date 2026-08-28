# R8 keep rules for the release build (isMinifyEnabled = true, see app/build.gradle.kts).
# Most libraries (Retrofit, OkHttp, Room, Hilt, Glance) ship their own consumer rules; the entries
# below cover the app's own reflection surfaces that R8 cannot see: kotlinx.serialization codegen
# and MapLibre's JNI.

# ---- Attributes needed by serialization / generics ----
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault
-keepattributes Signature, InnerClasses, EnclosingMethod

# ---- kotlinx.serialization (canonical rules) ----
# Keep the synthetic `Companion` field and generated `serializer()` methods that the plugin emits
# for every @Serializable class, so R8 doesn't strip/rename what the serializer resolves by name.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Belt-and-braces: keep the app's own DTOs (and their fields/enum constants) intact. They are
# decoded by name via the kotlinx.serialization Retrofit converter, so field/enum names must survive.
-keep @kotlinx.serialization.Serializable class com.bustramnantes.app.data.remote.dto.** { *; }
-keep @kotlinx.serialization.Serializable class com.bustramnantes.app.data.remote.opendata.** { *; }
-keepclassmembers class com.bustramnantes.app.**$$serializer { *; }

# ---- MapLibre (native/JNI: reflected + called from C++) ----
-keep class org.maplibre.** { *; }
-dontwarn org.maplibre.**

# ---- Kotlin metadata / coroutines (defensive) ----
-dontwarn kotlinx.coroutines.**
-dontwarn org.jetbrains.annotations.**
