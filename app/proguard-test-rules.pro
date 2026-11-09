# R8 rules for the instrumented-test APK only (used when CI runs the smoke tests against the shrunk
# release build, see -PciDebugSigning). Never packaged into the app.

# google-errorprone annotations (a compile-only transitive dep of the androidx.test libraries)
# reference javax.lang.model, which does not exist on Android and is never loaded at runtime.
-dontwarn javax.lang.model.element.Modifier
