plugins {
    id("com.android.library")
}

/*
 * The on-phone language model's native library, libdevkeyboard_llm.so: llama.cpp and the keyboard's way into it
 * (src/main/cpp). It is a module of its own because llama.cpp and whisper.cpp each bring their own copy of ggml, under
 * the same target names, and one CMake build cannot hold both; the app's own CMake build stays whisper's. There is
 * no Kotlin here: the class its functions belong to (Llm.kt) is in the app.
 */
val app = project(":app").extensions.getByType(com.android.build.api.dsl.ApplicationExtension::class.java)

android {
    namespace = "com.infamousvague.devkeyboard.llm"
    // The app's own, which are Flutter's: one compiler and one platform for both libraries.
    compileSdk = app.compileSdk
    ndkVersion = app.ndkVersion

    defaultConfig {
        minSdk = 26
        // arm64 only, as the app is.
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=c++_static") } }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}
