plugins { id("com.android.application") }

android {
    namespace = "com.stringpage.app"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "com.stringpage.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 20
        versionName = "1.18.0"
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
