# R8 rules for the release build.

# kotlinx.serialization generates a companion .serializer() for every @Serializable class and
# looks it up reflectively. Without these rules R8 strips the generated serializers and every
# API response fails to parse in release but works perfectly in debug, which is a miserable
# class of bug to discover after a demo has been recorded.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class za.co.techies.healthtracker.**$$serializer { *; }
-keepclassmembers class za.co.techies.healthtracker.** {
    *** Companion;
}
-keepclasseswithmembers class za.co.techies.healthtracker.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Retrofit builds its service implementations from the interface's generic signatures and
# annotations at runtime, so both have to survive shrinking.
-keepattributes Signature, Exceptions, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keep,allowobfuscation interface za.co.techies.healthtracker.data.remote.TechiesApi
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# OkHttp references optional platform classes that are absent on Android.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
