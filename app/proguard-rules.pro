# ZXing — keep reflective entry points.
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# BouncyCastle — keep all crypto primitives we touch via reflection or service-loader.
-keep class org.bouncycastle.crypto.** { *; }
-keep class org.bouncycastle.math.** { *; }
-keep class org.bouncycastle.util.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
-dontwarn org.bouncycastle.jce.provider.**

# Kotlin coroutines internals accessed reflectively.
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Preserve our public entry points used by the IME service / instrumentation.
-keep class com.wentuyi.app.TextImageImeService { *; }
-keep class com.wentuyi.app.ImageContentProvider { *; }
-keep class com.wentuyi.app.WentuyiSmokeInstrumentation { *; }
