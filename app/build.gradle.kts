plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

val ciDebugSigning = providers.gradleProperty("ciDebugSigning").isPresent
val ciReleaseTests = providers.gradleProperty("ciReleaseTests").isPresent

android {
    namespace = "com.example.finsightai"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.finsight.ai"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // llama.cpp is built for 64-bit ARM only (all current Android phones); see src/main/cpp.
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                arguments += "-DANDROID_STL=c++_shared"
                arguments += "-DBUILD_SHARED_LIBS=ON"
                arguments += "-DLLAMA_BUILD_COMMON=ON"
                arguments += "-DLLAMA_BUILD_TESTS=OFF"
                arguments += "-DLLAMA_BUILD_TOOLS=OFF"
                arguments += "-DLLAMA_BUILD_EXAMPLES=OFF"
                arguments += "-DLLAMA_BUILD_SERVER=OFF"
                arguments += "-DLLAMA_BUILD_APP=OFF"
                arguments += "-DLLAMA_OPENSSL=OFF"
                arguments += "-DLLAMA_CURL=OFF"
                arguments += "-DGGML_NATIVE=OFF"
                arguments += "-DGGML_LLAMAFILE=OFF"
                // Runtime CPU-feature dispatch: one CPU backend variant per feature level,
                // chosen at load time so a single APK is fast on new phones and safe on old ones.
                arguments += "-DGGML_BACKEND_DL=ON"
                arguments += "-DGGML_CPU_ALL_VARIANTS=ON"
            }
        }
    }

    ndkVersion = "28.2.13676358"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    // Release signing: only configured when all four Gradle properties are supplied (e.g. -P flags or
    // ~/.gradle/gradle.properties). No keystore or password is ever committed; without them the
    // release APK stays unsigned.
    val releaseSigningKeys = listOf(
        "FINSIGHT_RELEASE_STORE_FILE",
        "FINSIGHT_RELEASE_STORE_PASSWORD",
        "FINSIGHT_RELEASE_KEY_ALIAS",
        "FINSIGHT_RELEASE_KEY_PASSWORD",
    )
    val releaseSigningProps = releaseSigningKeys.mapNotNull { key ->
        providers.gradleProperty(key).orNull?.let { key to it }
    }.toMap()
    val hasReleaseSigning = releaseSigningProps.size == releaseSigningKeys.size
    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseSigningProps.getValue("FINSIGHT_RELEASE_STORE_FILE"))
                storePassword = releaseSigningProps.getValue("FINSIGHT_RELEASE_STORE_PASSWORD")
                keyAlias = releaseSigningProps.getValue("FINSIGHT_RELEASE_KEY_ALIAS")
                keyPassword = releaseSigningProps.getValue("FINSIGHT_RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            testProguardFiles("proguard-test-rules.pro")
            // CI only (-PciDebugSigning): sign the release APK with the auto-generated debug key so
            // it can be installed on the emulator. Never used for publishing; no keystore is committed.
            // Takes precedence over the release key, so CI behaves exactly as before.
            if (ciDebugSigning) {
                signingConfig = signingConfigs.getByName("debug")
            }
            // CI only (-PciReleaseTests): lets the androidx.test runner start inside the shrunk app.
            if (ciReleaseTests) {
                proguardFile("proguard-ci-test-rules.pro")
            }
        }
    }
    // CI only (-PciReleaseTests): run the instrumented smoke tests against the shrunk release APK.
    if (ciReleaseTests) {
        testBuildType = "release"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = false
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
      jniLibs {
        // ggml discovers its CPU backend variants by scanning nativeLibraryDir, so the
        // libraries must be extracted to disk rather than mapped from the APK.
        useLegacyPackaging = true
      }
    }

    testOptions {
      unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core library desugaring: java.time etc. on minSdk 24
  coreLibraryDesugaring(libs.desugar.jdk.libs)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation("androidx.compose.material:material-icons-extended")
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  androidTestImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)
}
