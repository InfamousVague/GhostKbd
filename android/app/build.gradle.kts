import java.util.Properties

plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

/*
 * THE RELEASE KEY. Android installs an update only over an app signed with the same certificate, so every release is
 * signed with one key kept outside the repository: ~/.config/devkeyboard/signing.properties (or the file
 * $DEVKEYBOARD_SIGNING names) with storeFile, storePassword, keyAlias and keyPassword. tool/release.mjs makes it the
 * first time. Without it a release build is signed with this machine's debug key, and tool/release.mjs refuses to
 * publish that.
 */
val signingFile = file(System.getenv("DEVKEYBOARD_SIGNING") ?: "${System.getProperty("user.home")}/.config/devkeyboard/signing.properties")
val signing = Properties().apply { if (signingFile.exists()) signingFile.inputStream().use { load(it) } }

android {
    namespace = "com.infamousvague.devkeyboard"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "com.infamousvague.devkeyboard"
        minSdk = 26
        // Written out, not flutter.targetSdkVersion: a newer Flutter must not raise it by itself. Built for Android 17
        // (37), the app would be given one-time-code texts three hours late there (docs/sms-codes.md).
        targetSdk = 36
        versionCode = flutter.versionCode
        versionName = flutter.versionName
        // arm64 only: the phones this is for, and whisper.cpp built once rather than four times.
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
                // The line above did not keep whisper to arm64: Flutter's plugin adds its own three to that list, and
                // up to 0.16.3 every APK carried whisper for 32-bit ARM and x86_64 as well, 3 MB that no phone could
                // use (Flutter's own libraries are in it for arm64 alone, so the app cannot start on another). This
                // one is the native build's own list, which nothing else adds to.
                abiFilters += listOf("arm64-v8a")
            }
        }
        // The GIF plugin's GIPHY key, from CI's GIPHY_API_KEY secret (empty: the plugin asks for one in Settings).
        buildConfigField("String", "GIPHY_KEY", "\"${System.getenv("GIPHY_API_KEY") ?: ""}\"")
    }

    buildFeatures { buildConfig = true }

    // The dictation plugin's speech to text: whisper.cpp in one JNI library (src/main/cpp).
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        if (signing.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}

dependencies {
    // The on-phone language model's library (android/llm: llama.cpp behind llm_jni.cpp), for Rewrite on this phone.
    implementation(project(":llm"))
    // The passwords plugin: the style Android's inline autofill suggestions are drawn in.
    implementation("androidx.autofill:autofill:1.1.0")
    // The undo history's self-check (src/test): plain JUnit on a desk, no device.
    testImplementation("junit:junit:4.13.2")
    // The accuracy replay (src/test AccuracyReplay) reads the record's JSON lines off the device.
    testImplementation("org.json:json:20240303")
}

// tool/accuracy-replay.sh passes the record with -PaccuracyLog=<file>: the replay test reads it, and runs again when it changes.
tasks.withType<Test>().configureEach {
    val log = (project.findProperty("accuracyLog") as String?).orEmpty()
    systemProperty("accuracyLog", log)
    inputs.property("accuracyLog", log)
    if (log.isNotEmpty() && file(log).exists()) inputs.file(log)
    testLogging { showStandardStreams = log.isNotEmpty() }
}
