import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    targets.withType<KotlinNativeTarget>().configureEach {
        val konanProperty =
            when (name) {
                "iosArm64" -> "osVersionMin.ios_arm64"
                "iosSimulatorArm64" -> "osVersionMin.ios_simulator_arm64"
                else -> error("Unexpected iOS target: $name")
            }
        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions.freeCompilerArgs.add(
                    "-Xoverride-konan-properties=$konanProperty=14.0"
                )
            }
        }
        binaries.framework {
            baseName = "ComposettyKit"
            isStatic = true
        }
    }

    sourceSets {
        iosMain.dependencies {
            implementation(project(":"))
            implementation(libs.compose.foundation)
            implementation(libs.compose.runtime)
            implementation(libs.compose.ui)
        }
    }
}
