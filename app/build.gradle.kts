import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import com.android.build.api.instrumentation.InstrumentationScope
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

plugins { id("com.android.application") }

// alphaTab 1.8.4 clamps both its synth and external-media clocks at 0.125×.
// Adjust the shared limit without replacing the parser or either playback path.
abstract class AlphaTabPlaybackSpeedVisitor : AsmClassVisitorFactory<InstrumentationParameters.None> {
    override fun isInstrumentable(classData: ClassData) =
        classData.className == "alphaTab.synth.SynthConstants"

    override fun createClassVisitor(classContext: ClassContext, nextClassVisitor: ClassVisitor): ClassVisitor =
        object : ClassVisitor(Opcodes.ASM9, nextClassVisitor) {
            private var replacements = 0

            override fun visitMethod(
                access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?
            ): MethodVisitor {
                val method = super.visitMethod(access, name, descriptor, signature, exceptions)
                if (name != "<clinit>") return method
                return object : MethodVisitor(Opcodes.ASM9, method) {
                    override fun visitLdcInsn(value: Any) {
                        if (value == 0.125) {
                            replacements++
                            super.visitLdcInsn(0.1)
                        } else {
                            super.visitLdcInsn(value)
                        }
                    }
                }
            }

            override fun visitEnd() {
                check(replacements == 1) { "Unexpected alphaTab 1.8.4 playback speed initializer" }
                super.visitEnd()
            }
        }
}

// Keep lyrics on their original beats, but have the TAB renderer place them
// below the staff. SharedBottom also handles the standard-notation-only view.
abstract class AlphaTabLyricsPlacementVisitor : AsmClassVisitorFactory<InstrumentationParameters.None> {
    override fun isInstrumentable(classData: ClassData) = classData.className == "alphaTab.Environment"

    override fun createClassVisitor(classContext: ClassContext, nextClassVisitor: ClassVisitor): ClassVisitor =
        object : ClassVisitor(Opcodes.ASM9, nextClassVisitor) {
            private var replacements = 0

            override fun visitMethod(
                access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?
            ): MethodVisitor {
                val method = super.visitMethod(access, name, descriptor, signature, exceptions)
                if (name != "<clinit>") return method
                return object : MethodVisitor(Opcodes.ASM9, method) {
                    private var readingLyricsBand = false

                    override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                        if (opcode == Opcodes.INVOKESPECIAL && owner == "alphaTab/rendering/effects/LyricsEffectInfo" &&
                            name == "<init>" && descriptor == "()V") readingLyricsBand = true
                    }

                    override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
                        if (!readingLyricsBand) {
                            super.visitFieldInsn(opcode, owner, name, descriptor)
                            return
                        }
                        check(opcode == Opcodes.GETSTATIC && owner == "alphaTab/rendering/EffectBandMode" &&
                            name == "SharedTop" && descriptor == "LalphaTab/rendering/EffectBandMode;") {
                            "Unexpected alphaTab 1.8.4 lyrics placement"
                        }
                        readingLyricsBand = false
                        replacements++
                        super.visitFieldInsn(opcode, owner, "SharedBottom", descriptor)
                    }
                }
            }

            override fun visitEnd() {
                check(replacements == 1) { "Expected one alphaTab 1.8.4 lyrics effect band" }
                super.visitEnd()
            }
        }
}

androidComponents.onVariants { variant ->
    variant.instrumentation.transformClassesWith(
        AlphaTabPlaybackSpeedVisitor::class.java, InstrumentationScope.ALL
    ) {}
    variant.instrumentation.transformClassesWith(
        AlphaTabLyricsPlacementVisitor::class.java, InstrumentationScope.ALL
    ) {}
}

android {
    namespace = "com.stringpage.app"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "com.stringpage.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 53
        versionName = "1.34.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64") }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_static" } }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    signingConfigs {
        if (System.getenv("STRINGPAGE_KEYSTORE") != null) {
            create("localRelease") {
                storeFile = file(System.getenv("STRINGPAGE_KEYSTORE"))
                storePassword = System.getenv("STRINGPAGE_STORE_PASSWORD")
                keyAlias = System.getenv("STRINGPAGE_KEY_ALIAS")
                keyPassword = System.getenv("STRINGPAGE_STORE_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("debug") {
            signingConfigs.findByName("localRelease")?.let { signingConfig = it }
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfigs.findByName("localRelease")?.let { signingConfig = it }
        }
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation("net.alphatab:alphaTab:1.8.4")
    implementation("androidx.appcompat:appcompat:1.7.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
