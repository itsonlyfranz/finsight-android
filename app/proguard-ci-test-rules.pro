# Applied to the app's R8 pass ONLY when building with -PciDebugSigning (the CI smoke run that
# instruments the shrunk release APK). The shipped release build never reads this file.
#
# The test APK is shrunk separately and relies on libraries that it expects to find in the app
# (the Kotlin stdlib and androidx.tracing). The app does not use all of them, so R8 would strip them
# and the test runner would crash with NoClassDefFoundError before any test runs. Keep only those
# shared libraries; the app's own code, Compose, and the JNI bridge rules in proguard-rules.pro are
# shrunk exactly as in the shipped build.
-keep class kotlin.** { *; }
-keep class androidx.tracing.** { *; }
-dontwarn kotlin.**
