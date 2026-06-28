plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "sh.haven.core.spice"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // Include native libraries built from Rust source by spice-kotlin:buildSpiceNative
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("${rootProject.projectDir}/spice-kotlin/jniLibs")
        }
    }
}

// Ensure Rust native library is built before this module compiles
tasks.configureEach {
    if (name == "preBuild") {
        dependsOn(gradle.includedBuild("spice-kotlin").task(":buildSpiceNative"))
    }
}

dependencies {
    api("sh.haven:spice-transport:0.1.0")
    implementation(libs.jna) { artifact { type = "aar" } }
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
