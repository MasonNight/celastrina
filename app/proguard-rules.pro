# R8 rules for the release build.
#
# Extensions are DEX compiled against CloudStream (ADR 0001), loaded at run time, and link by name against what
# Celastrina bundles. R8 can't see them, so it would rename or remove every class, method and field that Celastrina's
# own code doesn't use. Everything an Extension can link against is kept whole and unrenamed:
#
# - CloudStream's `library` (com.lagradost.cloudstream3, com.lagradost.api) and Celastrina's shims, which use the same
#   package names (com.lagradost.cloudstream3.plugins.Plugin).
# - The libraries CloudStream gives its plugins and that the `library` brings: NiceHttp, OkHttp and Okio, Jackson with
#   its Kotlin module, jsoup, Rhino, FuzzyWuzzy, NewPipeExtractor and its nanojson, tmdb-java with Retrofit and Gson,
#   and the Kotlin standard library, kotlin-reflect and kotlinx.coroutines.
#
# The emulator test (ExtensionLoadingTest, run with -PtestBuildType=release) loads the test Extension against this.

-keep class com.lagradost.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class org.jsoup.** { *; }
-keep class org.mozilla.** { *; }
-keep class me.xdrop.fuzzywuzzy.** { *; }
-keep class org.schabi.newpipe.** { *; }
-keep class com.grack.nanojson.** { *; }
-keep class com.uwetrottmann.tmdb2.** { *; }
-keep class retrofit2.** { *; }
-keep class com.google.gson.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }

# Kotlin metadata, which Kotlin-compiled Extensions and Jackson's Kotlin module read through kotlin-reflect, and the
# generic signatures, annotations and inner-class links they read through Java reflection.
-keep class kotlin.Metadata { *; }
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,RuntimeVisibleTypeAnnotations,AnnotationDefault
-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions,MethodParameters,SourceFile,LineNumberTable

# Classes Android lacks that the kept libraries reach only on paths Extensions don't take on Android: jsoup's optional
# RE2/J regex engine and JSpecify annotations, Jackson's and Rhino's java.beans support, and Rhino's javax.script engine
# and invokedynamic linker.
-dontwarn com.google.re2j.**
-dontwarn org.jspecify.annotations.**
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**

# The instrumented tests run against this APK (-PtestBuildType=release). The test APK links to Celastrina's classes
# and to androidx.tracing, which AndroidJUnitRunner calls and the app carries for OkHttp's startup initializer.
# Keeping Celastrina's names also keeps the TV's stack traces readable. The tests read JSON with Android's org.json.
# A dependency also bundles org.json:json, which R8 would otherwise rename under the tests (InstantiationError).
-keep class dev.celastrina.** { *; }
-keep class androidx.tracing.Trace { *; }
-keep class org.json.** { *; }

# The redirected HLS test calls these Media3 APIs from the test APK and implements the error callback.
-keep interface androidx.media3.exoplayer.ExoPlayer {
    void release();
}
-keep interface androidx.media3.common.Player {
    void addListener(androidx.media3.common.Player$Listener);
    long getCurrentPosition();
}
-keep interface androidx.media3.common.Player$Listener {
    void onPlayerError(androidx.media3.common.PlaybackException);
}
-keep class androidx.media3.common.PlaybackException {
    java.lang.String getErrorCodeName();
}
