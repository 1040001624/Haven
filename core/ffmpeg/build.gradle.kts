plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "sh.haven.core.ffmpeg"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
}

// Copy pre-built FFmpeg binaries into jniLibs so Android extracts them
// to nativeLibraryDir. Same pattern as core/local's buildProot task.
// Refreshes each ABI from a local `build-ffmpeg/build-<abi>` if present;
// ABIs without a local build keep their committed .so (so CI/F-Droid,
// which uses the committed binaries, is never clobbered with nothing).
val ffmpegAbis = listOf("arm64-v8a", "x86_64", "armeabi-v7a")
val copyFfmpegBinaries by tasks.registering {
    doLast {
        ffmpegAbis.forEach { abi ->
            val buildDir = rootProject.file("build-ffmpeg/build-$abi/install/bin")
            if (buildDir.exists()) {
                copy {
                    from(buildDir) {
                        include("libffmpeg.so", "libffprobe.so", "libc++_shared.so")
                    }
                    into(file("src/main/jniLibs/$abi"))
                }
            }
        }
    }
}

tasks.named("preBuild") {
    dependsOn(copyFfmpegBinaries)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
