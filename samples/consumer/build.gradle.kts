import java.util.zip.ZipFile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
    alias(libs.plugins.android.kmp.library)
}

val composettyVersion = providers.gradleProperty("composettyVersion").get()
val repositoryRoot = file("../../build/consumer-repository")
val iosPublished =
    repositoryRoot
        .resolve("dev/befrvnk/composetty/ghostty-compose-iosarm64/$composettyVersion")
        .isDirectory

kotlin {
    jvmToolchain(17)
    jvm()
    if (iosPublished) {
        iosArm64()
        iosSimulatorArm64()
    }

    android {
        namespace = "dev.befrvnk.composetty.consumer"
        compileSdk = 36
        minSdk = 26
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        withHostTestBuilder {}
    }

    sourceSets {
        commonMain.dependencies {
            implementation("dev.befrvnk.composetty:ghostty-compose:$composettyVersion")
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        jvmTest.dependencies { implementation(kotlin("test-junit")) }
    }
}

val verifyAndroidAppNativeLibraries =
    tasks.register("verifyAndroidAppNativeLibraries") {
        group = "verification"
        description = "Verifies native libraries from the published AAR are packaged in an APK"
        dependsOn(":android-app:assembleDebug")
        val apkDirectory = project(":android-app").layout.buildDirectory.dir("outputs/apk/debug")
        inputs.dir(apkDirectory)
        doLast {
            val apk =
                apkDirectory.get().asFile.listFiles().orEmpty().single { it.extension == "apk" }
            ZipFile(apk).use { archive ->
                val expected =
                    listOf(
                        "lib/arm64-v8a/libcomposetty-ghostty.so",
                        "lib/x86_64/libcomposetty-ghostty.so",
                    )
                val missing = expected.filter { archive.getEntry(it) == null }
                check(missing.isEmpty()) {
                    "Published Android native libraries missing from ${apk.name}: $missing"
                }
            }
        }
    }

tasks.register("consumerCheck") {
    group = "verification"
    description = "Checks every platform available in the published test repository"
    dependsOn("check", "assembleAndroidMain", verifyAndroidAppNativeLibraries)
    if (iosPublished) {
        dependsOn("iosSimulatorArm64Test", "linkDebugTestIosArm64")
    }
}
