import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.openstream.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.openstream.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++20"
                val enableLibsrt = providers.gradleProperty("openstream.enableLibsrt").orNull == "true"
                arguments += "-DOPENSTREAM_ENABLE_LIBSRT=${if (enableLibsrt) "ON" else "OFF"}"
                providers.gradleProperty("openstream.libsrtIncludeDir").orNull?.let {
                    arguments += "-DOPENSTREAM_LIBSRT_INCLUDE_DIR=$it"
                }
                providers.gradleProperty("openstream.libsrtLibrary").orNull?.let {
                    arguments += "-DOPENSTREAM_LIBSRT_LIBRARY=$it"
                }
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
