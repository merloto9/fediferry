# kotlinx.serialization keeps its generated serializers via companion objects.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class app.fediferry.**$$serializer { *; }
-keepclasseswithmembers class app.fediferry.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp pulls in optional platform classes that are absent on Android.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Tink (pulled in by androidx.security.crypto) references errorprone annotations
# that are compile-only and absent at runtime.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
