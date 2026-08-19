import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

compose.desktop {
    application {
        mainClass = "dev.befrvnk.composetty.sample.remote.MainKt"
    }
}

kotlin {
    jvm()

    android {
        namespace = "dev.befrvnk.composetty.sample.remote"
        compileSdk = 36
        minSdk = 26
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":"))
            implementation(libs.compose.foundation)
            implementation(libs.compose.runtime)
            implementation(libs.compose.ui)
            implementation(libs.coroutines.core)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
        }
        androidMain.dependencies { implementation(libs.ktor.client.android) }
        jvmMain.dependencies { implementation(libs.ktor.client.cio) }
        commonTest.dependencies { implementation(kotlin("test")) }
        jvmTest.dependencies {
            implementation(libs.junit)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.websockets)
        }
    }
}
