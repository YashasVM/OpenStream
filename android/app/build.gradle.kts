import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import com.android.build.api.instrumentation.InstrumentationScope
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

// RootEncoder logs AMF publish commands, including the key, even with setLogs(false).
// Remove its Android Log calls in both debug and release dependency bytecode.
abstract class QuietTransportLogs : AsmClassVisitorFactory<InstrumentationParameters.None> {
    override fun isInstrumentable(classData: ClassData) = classData.className.startsWith("com.pedro.")

    override fun createClassVisitor(classContext: ClassContext, nextClassVisitor: ClassVisitor): ClassVisitor =
        object : ClassVisitor(Opcodes.ASM9, nextClassVisitor) {
            override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor {
                val next = super.visitMethod(access, name, descriptor, signature, exceptions)
                return object : MethodVisitor(Opcodes.ASM9, next) {
                    override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
                        if (opcode == Opcodes.INVOKESTATIC && owner == "android/util/Log") {
                            Type.getArgumentTypes(descriptor).reversed().forEach {
                                super.visitInsn(if (it.size == 2) Opcodes.POP2 else Opcodes.POP)
                            }
                            if (Type.getReturnType(descriptor).sort == Type.INT) super.visitInsn(Opcodes.ICONST_0)
                        } else super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                    }
                }
            }
        }
}


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
    .get()
    .removePrefix("v")
val openStreamVersionCode = providers.gradleProperty("openstream.versionCode")
    .map { it.toInt() }
    .get()
// Development APKs are commonly shared outside Gradle's install task. Give
// each build a newer code so Android accepts an in-place update with the same
// debug signing key, while releases keep the reviewed code in gradle.properties.
val developmentVersionCode = maxOf(
    openStreamVersionCode,
    (System.currentTimeMillis() / 1000).toInt(),
)

android {
    namespace = "dev.openstream.app"
    compileSdk = 35
    ndkVersion = "27.1.12297006"

    defaultConfig {
        // Keep the published OpenStream identity so existing installs update
        // in place when they use the same release signing key.
        applicationId = "dev.openstream.app"
        minSdk = 29
        targetSdk = 35
        versionCode = openStreamVersionCode
        versionName = openStreamVersionName

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
        debug {
            versionNameSuffix = "-dev"
            if (providers.gradleProperty("openstream.testApplicationIdSuffix").orNull == "true") {
                applicationIdSuffix = ".test"
            }
        }
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

androidComponents {
    onVariants { variant ->
        variant.instrumentation.transformClassesWith(QuietTransportLogs::class.java, InstrumentationScope.ALL) {}
    }
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.outputs.forEach { output ->
            output.versionCode.set(developmentVersionCode)
        }
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
    implementation("com.github.pedroSG94.RootEncoder:rtmp:2.8.1")
    testImplementation("junit:junit:4.13.2")
    // Unit tests run on the local JVM against android.jar stubs, where
    // org.json.JSONObject methods throw "not mocked". Ship the real
    // org.json implementation for tests only; on-device code keeps using
    // the platform's built-in org.json.
    testImplementation("org.json:json:20240303")
}
