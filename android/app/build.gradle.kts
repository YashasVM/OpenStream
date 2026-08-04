import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseKeystorePath = providers.environmentVariable("OPENSTREAM_RELEASE_KEYSTORE").orNull
val releaseStorePassword = providers.environmentVariable("OPENSTREAM_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("OPENSTREAM_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("OPENSTREAM_RELEASE_KEY_PASSWORD").orNull
val hasReleaseSigning = listOf(
    releaseKeystorePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }
val openStreamVersionName = providers.gradleProperty("openstream.versionName")
    .orElse(providers.environmentVariable("OPENSTREAM_VERSION_NAME"))
    .orElse("2.0.0-beta")
    .map { it.removePrefix("v") }
val openStreamVersionCode = providers.gradleProperty("openstream.versionCode")
    .orElse(providers.environmentVariable("OPENSTREAM_VERSION_CODE"))
    .orElse("21")
    .map { it.toInt() }

android {
    namespace = "dev.openstream.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.openstream.app"
        minSdk = 29
        targetSdk = 35
        versionCode = openStreamVersionCode.get()
        versionName = openStreamVersionName.get()

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++20"
                val nonStreamingCiBuild =
                    providers.gradleProperty("openstream.nonStreamingCiBuild").orNull == "true"
                val enableLibsrt =
                    providers.gradleProperty("openstream.enableLibsrt").orNull?.toBooleanStrictOrNull()
                        ?: !nonStreamingCiBuild
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

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseStorePassword!!
                keyAlias = releaseKeyAlias!!
                keyPassword = releaseKeyPassword!!
            }
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
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

gradle.taskGraph.whenReady {
    val releaseTaskRequested = allTasks.any { task ->
        task.path == ":app:assembleRelease" ||
            task.path == ":app:bundleRelease" ||
            task.path == ":app:packageRelease"
    }
    if (releaseTaskRequested && !hasReleaseSigning) {
        throw org.gradle.api.GradleException(
            "Release builds require OPENSTREAM_RELEASE_KEYSTORE, " +
                "OPENSTREAM_RELEASE_STORE_PASSWORD, OPENSTREAM_RELEASE_KEY_ALIAS, " +
                "and OPENSTREAM_RELEASE_KEY_PASSWORD.",
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
