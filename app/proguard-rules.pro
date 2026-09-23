# kotlinx.serialization: keep generated serializers of @Serializable classes.
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations, AnnotationDefault
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Retrofit service interfaces are used through reflection.
-keep,allowobfuscation interface ru.timegrip.app.data.remote.TimeGripApi
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
