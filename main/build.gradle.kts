import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskProvider

/*
 * Copyright (c) 2012-2016 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */

plugins {
    alias(libs.plugins.android.application)
}

fun obtainTestBuildType(): String {
    var result = "debug";

    if (project.hasProperty("testBuildType")) {
        result = project.property("testBuildType").toString()
    }
    return result
}

android {
    buildFeatures {
        aidl = true
        buildConfig = true
    }
    namespace = "de.blinkt.openvpn"
    compileSdk = 37
    //compileSdkPreview = "UpsideDownCake"

    ndkVersion = "30.0.14904198"

    defaultConfig {
        applicationId = "com.paperline.vpn"
        minSdk = 26
        targetSdk = 35
        //targetSdkPreview = "UpsideDownCake"
        versionCode = 24
        versionName = "1.23"
        externalNativeBuild {
            cmake {
                //arguments+= "-DCMAKE_VERBOSE_MAKEFILE=1"
            }
        }
    }

    androidResources {
        generateLocaleConfig = true
    }

    externalNativeBuild {
        cmake {
            path = File("${projectDir}/src/main/cpp/CMakeLists.txt")
        }
    }

    sourceSets {
        getByName("main") {
            assets.directories.add("build/ovpnassets")
        }

        create("ui") {}

        create("skeleton") {}

        getByName("debug") {}

        getByName("release") {}
    }

    lint {
        enable += setOf(
            "BackButton",
            "EasterEgg",
            "StopShip",
            "IconExpectedSize",
            "GradleDynamicVersion",
            "NewerVersionAvailable"
        )
        checkOnly += setOf("ImpliedQuantity", "MissingQuantity")
        disable += setOf("MissingTranslation", "UnsafeNativeCodeLocation")
    }


    flavorDimensions += listOf("implementation", "ovpnimpl")

    productFlavors {
        create("ui") {
            dimension = "implementation"
        }

        create("skeleton") {
            dimension = "implementation"
        }

        create("ovpn23") {
            dimension = "ovpnimpl"
            buildConfigField("boolean", "openvpn3", "true")
        }

        create("ovpn2") {
            dimension = "ovpnimpl"
            versionNameSuffix = "-o2"
            buildConfigField("boolean", "openvpn3", "false")
        }
    }

    buildTypes {
        getByName("release") {
            optimization {
                enable = false
            }
        }
        getByName("debug")
        {
            optimization {
                enable = false
            }
        }
    }

    testBuildType = obtainTestBuildType()

    compileOptions {
        targetCompatibility = JavaVersion.VERSION_17
        sourceCompatibility = JavaVersion.VERSION_17
    }

    /* Disable due to https://issuetracker.google.com/issues/402800800 */
    splits {
        abi {
            isEnable = false
            reset()
            include("x86", "x86_64", "armeabi-v7a", "arm64-v8a")
            isUniversalApk = true
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

abstract class BuildSetuRustCore : Exec() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty
}

val setuRustCore = tasks.register<BuildSetuRustCore>("buildSetuRustCore") {
    val rustSource = rootProject.layout.projectDirectory.dir("rust-core")
    inputs.dir(rustSource.dir("src"))
    inputs.files(rustSource.file("Cargo.toml"), rustSource.file("Cargo.lock"), rustSource.file("build-android.sh"))
    outputDir.set(layout.buildDirectory.dir("generated/setuRustCore"))
    environment("CARGO_TARGET_DIR", layout.buildDirectory.dir("setu-cargo").get().asFile.absolutePath)
    doFirst {
        val ndk = providers.environmentVariable("ANDROID_NDK_HOME").orNull
            ?: androidComponents.sdkComponents.ndkDirectory.get().asFile.absolutePath
        commandLine("bash", rustSource.file("build-android.sh").asFile.absolutePath,
            ndk, outputDir.get().asFile.absolutePath)
    }
}

var swigcmd = "swig"
// Workaround for macOS(arm64) and macOS(intel) since it otherwise does not find swig and
// I cannot get the Exec task to respect the PATH environment :(
if (file("/opt/homebrew/bin/swig").exists()) swigcmd = "/opt/homebrew/bin/swig"
else if (file("/usr/local/bin/swig").exists()) swigcmd = "/usr/local/bin/swig"


abstract class GenerateSwigTask : Exec() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty
}

fun registerGenSwigTask(variantName: String, variantDirName: String): TaskProvider<GenerateSwigTask> {
    val baseDir = layout.buildDirectory.dir("generated/source/ovpn3swig/${variantDirName}")

    val genTask = tasks.register<GenerateSwigTask>("generateOpenVPN3Swig${variantName}") {
        val genDir = baseDir.get().asFile.resolve("net/openvpn/ovpn3")
        outputDir.set(baseDir)

        doFirst {
            mkdir(genDir)
        }
        commandLine(
            listOf(
                swigcmd,
                "-outdir",
                genDir.absolutePath,
                "-outcurrentdir",
                "-c++",
                "-java",
                "-package",
                "net.openvpn.ovpn3",
                "-Isrc/main/cpp/openvpn3/client",
                "-Isrc/main/cpp/openvpn3/",
                "-DOPENVPN_PLATFORM_ANDROID",
                "-o",
                "${genDir}/ovpncli_wrap.cxx",
                "-oh",
                "${genDir}/ovpncli_wrap.h",
                "src/main/cpp/openvpn3/client/ovpncli.i"
            )
        )
        inputs.files("src/main/cpp/openvpn3/client/ovpncli.i")

    }
    return genTask
}

androidComponents {
    onVariants(selector().all()) { variant ->
        val execTask = registerGenSwigTask(variant.name, variant.name.replace("-", "/"))
        variant.sources.java?.addGeneratedSourceDirectory(execTask, GenerateSwigTask::outputDir)
        variant.sources.jniLibs?.addGeneratedSourceDirectory(setuRustCore, BuildSetuRustCore::outputDir)
    }
}

dependencies {
    implementation("com.wireguard.android:tunnel:1.0.20260102")
    // https://maven.google.com/web/index.html
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.core.ktx)

    uiImplementation(libs.android.view.material)
    uiImplementation(libs.androidx.activity)
    uiImplementation(libs.androidx.activity.ktx)
    uiImplementation(libs.androidx.appcompat)
    uiImplementation(libs.androidx.cardview)
    uiImplementation(libs.androidx.viewpager2)
    uiImplementation(libs.androidx.constraintlayout)
    uiImplementation(libs.androidx.core.ktx)
    uiImplementation(libs.androidx.fragment.ktx)
    uiImplementation(libs.androidx.lifecycle.runtime.ktx)
    uiImplementation(libs.androidx.lifecycle.viewmodel.ktx)
    uiImplementation(libs.androidx.preference.ktx)
    uiImplementation(libs.androidx.recyclerview)
    uiImplementation(libs.androidx.security.crypto)
    uiImplementation(libs.kotlin)
    uiImplementation(libs.mpandroidchart)
    uiImplementation(libs.square.okhttp)

    testImplementation(libs.androidx.test.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin)
    testImplementation(libs.mockito.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.json)
}

fun DependencyHandler.uiImplementation(dependencyNotation: Any): Dependency? =
    add("uiImplementation", dependencyNotation)
