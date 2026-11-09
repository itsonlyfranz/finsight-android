# R8 rules for the release build. Keep this file minimal: every rule must be justified by
# something that is looked up by NAME at runtime and so cannot be seen by static analysis.
# Libraries (kotlinx-serialization, Compose, Navigation3, ...) ship their own consumer rules.

# --- llama.cpp JNI bridge (src/main/cpp/finsight_llm.cpp) -------------------------------------
# The JVM binds each `external fun` to the C symbol Java_<package>_<class>_<method>, so both the
# class and the exact names of its native methods must survive shrinking and obfuscation.
# (nativeInit, nativeSystemInfo, nativeLoad, nativeRelease, nativeGenerate)
-keep class com.example.finsightai.llm.LlamaNative {
    native <methods>;
}

# nativeGenerate() calls env->GetMethodID(GetObjectClass(sink), "onToken", "([B)Z"): a lookup by
# name on the runtime class of the sink object (a Kotlin SAM lambda). Keeping the interface method
# also pins the name on every implementation, including the synthetic lambda classes.
-keepclassmembers interface com.example.finsightai.llm.TokenSink {
    boolean onToken(byte[]);
}
