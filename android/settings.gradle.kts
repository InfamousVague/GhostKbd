pluginManagement {
    val flutterSdkPath =
        run {
            val properties = java.util.Properties()
            file("local.properties").inputStream().use { properties.load(it) }
            val flutterSdkPath = properties.getProperty("flutter.sdk")
            require(flutterSdkPath != null) { "flutter.sdk not set in local.properties" }
            flutterSdkPath
        }

    includeBuild("$flutterSdkPath/packages/flutter_tools/gradle")

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("dev.flutter.flutter-plugin-loader") version "1.0.0"
    // These two and the Gradle in gradle/wrapper/gradle-wrapper.properties are the ones Flutter 3.50.0 makes a new
    // project with (9.3.1, 2.4.20, Gradle 9.5.0). 3.50 refuses to build below 9.1.1, 2.4.10 and Gradle 9.3.1, and
    // warns below these three. Raise them together, with the Flutter the workflows name (README, "Build and run").
    id("com.android.application") version "9.3.1" apply false
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
}

include(":app")
