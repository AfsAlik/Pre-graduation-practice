# Модели обмена с сервером сериализуются по именам полей (kotlinx.serialization).
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class ru.finhelper.app.** {
    *** Companion;
}
-keepclasseswithmembers class ru.finhelper.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Библиотека SQLCipher обращается к своим классам из машинного кода.
-keep class net.zetetic.database.** { *; }
# Retrofit определяет типы ответов по сигнатурам методов интерфейса.
-keepattributes Signature, Exceptions
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
