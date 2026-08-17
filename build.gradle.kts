import java.io.File
import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.testing.Test
import org.gradle.language.jvm.tasks.ProcessResources
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

abstract class VerifyReleaseVersion : DefaultTask() {
    @get:Input abstract val releaseVersion: Property<String>

    @get:Input abstract val githubRefType: Property<String>

    @get:Input abstract val githubRefName: Property<String>

    @get:InputFile abstract val changelog: RegularFileProperty

    @TaskAction
    fun verify() {
        val value = releaseVersion.get()
        val releasePattern = Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-(?:alpha|beta|rc)[0-9]+)?")
        check(releasePattern.matches(value)) {
            "Release version '$value' must match ${releasePattern.pattern}"
        }
        check(!value.endsWith("-SNAPSHOT")) { "Release version must not be a snapshot" }
        if (githubRefType.get().isNotEmpty() || githubRefName.get().isNotEmpty()) {
            check(githubRefType.get() == "tag") { "Maven Central publishing requires a tag" }
            check(githubRefName.get() == "v$value") {
                "Git tag '${githubRefName.get()}' does not match version '$value'"
            }
        }
        val heading = "## [$value]"
        check(changelog.get().asFile.readText().contains(heading)) {
            "CHANGELOG.md does not contain '$heading'"
        }
    }
}

abstract class ConfirmMavenCentralUpload : DefaultTask() {
    @get:Input abstract val releaseVersion: Property<String>

    @get:Input abstract val confirmation: Property<String>

    @TaskAction
    fun verify() {
        val value = releaseVersion.get()
        check(confirmation.get() == value) {
            "Refusing to upload to Maven Central. Pass " +
                "-PconfirmMavenCentralUpload=$value to confirm this user-managed deployment."
        }
    }
}

abstract class VerifyLegalArchives : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val archives: ConfigurableFileCollection

    @get:Input abstract val requiredEntries: ListProperty<String>

    @TaskAction
    fun verify() {
        archives.files.forEach { archive ->
            ZipFile(archive).use { zip ->
                val missing = requiredEntries.get().filter { zip.getEntry(it) == null }
                check(missing.isEmpty()) {
                    "Missing legal resources from ${archive.name}:\n${missing.joinToString("\n")}"
                }
            }
        }
    }
}

plugins {
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
    alias(libs.plugins.maven.publish)
}

group = providers.gradleProperty("GROUP").get()

version = providers.gradleProperty("VERSION_NAME").get()

val iosNativeResources =
    providers
        .gradleProperty("composetty.iosNative")
        .orElse(providers.environmentVariable("COMPOSETTY_IOS_NATIVE"))
val iosEnabled = iosNativeResources.isPresent

kotlin {
    explicitApi()
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class) abiValidation()
    jvmToolchain(17)

    jvm()

    if (iosEnabled) {
        val nativeRoot = File(iosNativeResources.get())
        iosArm64 {
            compilations.configureEach {
                compileTaskProvider.configure {
                    compilerOptions.freeCompilerArgs.add(
                        "-Xoverride-konan-properties=osVersionMin.ios_arm64=14.0"
                    )
                }
            }
            compilations.getByName("main").cinterops.create("ghostty") {
                definitionFile.set(
                    layout.projectDirectory.file("src/nativeInterop/cinterop/ghostty.def")
                )
                includeDirs(nativeRoot.resolve("iosArm64/include"))
                extraOpts("-libraryPath", nativeRoot.resolve("iosArm64/lib").absolutePath)
            }
        }
        iosSimulatorArm64 {
            compilations.configureEach {
                compileTaskProvider.configure {
                    compilerOptions.freeCompilerArgs.add(
                        "-Xoverride-konan-properties=osVersionMin.ios_simulator_arm64=14.0"
                    )
                }
            }
            compilations.getByName("main").cinterops.create("ghostty") {
                definitionFile.set(
                    layout.projectDirectory.file("src/nativeInterop/cinterop/ghostty.def")
                )
                includeDirs(nativeRoot.resolve("iosSimulatorArm64/include"))
                extraOpts("-libraryPath", nativeRoot.resolve("iosSimulatorArm64/lib").absolutePath)
            }
        }
    }

    android {
        namespace = "dev.befrvnk.composetty"
        compileSdk = 36
        minSdk = 26

        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        optimization {
            consumerKeepRules.file("consumer-rules.pro")
            consumerKeepRules.publish = true
        }

        withHostTestBuilder {}
        withDeviceTestBuilder {}
            .configure {
                instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.compose.runtime)
            api(libs.compose.ui)
            api(libs.coroutines.core)

            implementation(libs.atomicfu)
            implementation(libs.compose.foundation)
        }

        jvmMain.dependencies {
            implementation(libs.jna)
            implementation(libs.pty4j)
        }

        commonTest.dependencies { implementation(kotlin("test")) }

        if (iosEnabled) {
            listOf("iosArm64Test", "iosSimulatorArm64Test").forEach { sourceSetName ->
                getByName(sourceSetName).dependencies { implementation(libs.compose.ui.test) }
            }
        }

        getByName("androidDeviceTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.androidx.test.runner)
        }

        jvmTest.dependencies { implementation(libs.junit) }
    }
}

val nativeResources =
    providers
        .gradleProperty("composetty.nativeResources")
        .orElse(providers.environmentVariable("COMPOSETTY_NATIVE_RESOURCES"))
val androidJniLibs =
    providers
        .gradleProperty("composetty.androidJniLibs")
        .orElse(providers.environmentVariable("COMPOSETTY_ANDROID_JNI_LIBS"))
val androidLegalResources = layout.buildDirectory.dir("generated/androidLegalResources")
val prepareAndroidLegalResources =
    tasks.register<Sync>("prepareAndroidLegalResources") {
        into(androidLegalResources.map { it.dir("META-INF") })
        from(layout.projectDirectory.file("LICENSE")) { rename { "LICENSE-composetty" } }
        from(layout.projectDirectory.file("NOTICE")) { rename { "NOTICE-composetty" } }
        from(layout.projectDirectory.file("src/jvmMain/resources/META-INF/LICENSE-ghostty"))
    }

androidComponents {
    onVariants { variant ->
        androidJniLibs.orNull?.let { directory ->
            variant.sources.jniLibs?.addStaticSourceDirectory(directory)
        }
        variant.sources.resources?.addStaticSourceDirectory(
            androidLegalResources.get().asFile.absolutePath
        )
    }
}

kotlin.sourceSets.named("jvmMain") {
    nativeResources.orNull?.let(resources::srcDir)
}

tasks
    .matching {
        it.name == "processAndroidMainJavaRes" || it.name == "mergeAndroidMainJavaResource"
    }
    .configureEach {
        dependsOn(prepareAndroidLegalResources)
    }

tasks
    .matching { it.name == "copyAndroidDeviceTestComposeResourcesToAndroidAssets" }
    .configureEach {
        // Compose creates this task without an output for Android KMP device tests when no Compose
        // resources exist. The terminal integration test does not use packaged Compose resources.
        enabled = false
    }

tasks.named<ProcessResources>("jvmProcessResources") {
    from(layout.projectDirectory.file("LICENSE")) { into("META-INF") }
    from(layout.projectDirectory.file("NOTICE")) { into("META-INF") }
    filePermissions { unix("rw-r--r--") }
    dirPermissions { unix("rwxr-xr-x") }
}

val iosLegalResources = layout.buildDirectory.dir("generated/iosLegalResources")
val prepareIosLegalResources =
    tasks.register<Sync>("prepareIosLegalResources") {
        into(iosLegalResources)
        from(layout.projectDirectory.file("LICENSE")) {
            into("META-INF")
            rename { "LICENSE-composetty" }
        }
        from(layout.projectDirectory.file("NOTICE")) {
            into("META-INF")
            rename { "NOTICE-composetty" }
        }
        from(layout.projectDirectory.file("src/jvmMain/resources/META-INF/LICENSE-ghostty")) {
            into("META-INF")
        }
    }

val iosLegalArchives = mutableListOf<TaskProvider<Zip>>()
if (iosEnabled) {
    listOf("iosArm64", "iosSimulatorArm64").forEach { targetName ->
        iosLegalArchives +=
            tasks.named<Zip>("${targetName}Cinterop-ghosttyKlib") {
                from(prepareIosLegalResources) { into("default/resources") }
            }
    }
}

val verifyIosLegalArtifacts =
    tasks.register<VerifyLegalArchives>("verifyIosLegalArtifacts") {
        group = "verification"
        description = "Verifies legal resources in the published iOS cinterop KLIBs"
        enabled = iosEnabled
        archives.from(iosLegalArchives)
        requiredEntries.set(
            listOf(
                "default/resources/META-INF/LICENSE-composetty",
                "default/resources/META-INF/LICENSE-ghostty",
                "default/resources/META-INF/NOTICE-composetty",
            )
        )
    }

tasks.named<Test>("jvmTest") { useJUnit() }

tasks.register("test") {
    group = "verification"
    description = "Runs the JVM test suite"
    dependsOn("jvmTest")
}

val cleanConsumerTestRepository =
    tasks.register<Delete>("cleanConsumerTestRepository") {
        delete(layout.buildDirectory.dir("consumer-repository"))
    }

tasks
    .matching {
        it.name.startsWith("publish") && it.name.endsWith("ToConsumerTestRepository")
    }
    .configureEach {
        dependsOn(cleanConsumerTestRepository)
    }

tasks.register<Exec>("consumerSmokeTest") {
    group = "verification"
    description = "Publishes to an isolated repository and checks a standalone consumer"
    dependsOn("publishAllPublicationsToConsumerTestRepository")
    workingDir(layout.projectDirectory.dir("samples/consumer"))
    commandLine(
        layout.projectDirectory.file("gradlew").asFile.absolutePath,
        "consumerCheck",
        "-PcomposettyVersion=$version",
        "--no-daemon",
        "--no-configuration-cache",
    )
}

val verifyReleaseVersion =
    tasks.register<VerifyReleaseVersion>("verifyReleaseVersion") {
        group = "verification"
        description = "Verifies the release version, Git tag, and changelog entry"
        releaseVersion.set(providers.provider { version.toString() })
        githubRefType.set(providers.environmentVariable("GITHUB_REF_TYPE").orElse(""))
        githubRefName.set(providers.environmentVariable("GITHUB_REF_NAME").orElse(""))
        changelog.set(layout.projectDirectory.file("CHANGELOG.md"))
    }

val confirmMavenCentralUpload =
    tasks.register<ConfirmMavenCentralUpload>("confirmCentralUpload") {
        group = "publishing"
        description = "Requires an explicit version confirmation before a Central upload"
        releaseVersion.set(providers.provider { version.toString() })
        confirmation.set(providers.gradleProperty("confirmMavenCentralUpload").orElse(""))
    }

val releasePreflight =
    tasks.register("releasePreflight") {
        group = "publishing"
        description = "Checks and signs a release without contacting Maven Central"
        dependsOn(
            verifyReleaseVersion,
            "check",
            "consumerSmokeTest",
            "checkSigningConfiguration",
            "checkPomFileForKotlinMultiplatformPublication",
            "checkPomFileForJvmPublication",
            "checkPomFileForAndroidPublication",
            "checkPomFileForIosArm64Publication",
            "checkPomFileForIosSimulatorArm64Publication",
        )
    }

val verifyReleaseNativeResources =
    tasks.register("verifyReleaseNativeResources") {
        group = "verification"
        description = "Verifies that all supported native libraries are ready for publication"
        inputs.dir(nativeResources)
        inputs.property("nativeResourcesRoot", nativeResources)
        doLast {
            val root = File(inputs.properties.getValue("nativeResourcesRoot").toString())
            val expected =
                listOf(
                    "native/ghostty/macos-arm64/libcomposetty-ghostty.dylib",
                    "native/ghostty/macos-x86_64/libcomposetty-ghostty.dylib",
                    "native/ghostty/linux-arm64/libcomposetty-ghostty.so",
                    "native/ghostty/linux-x86_64/libcomposetty-ghostty.so",
                )
            val missing = expected.filterNot { root.resolve(it).isFile }
            check(missing.isEmpty()) {
                "Missing native release resources:\n${missing.joinToString("\n")}"
            }
        }
    }

val verifyAndroidNativeResources =
    tasks.register("verifyAndroidNativeResources") {
        group = "verification"
        description = "Verifies that all supported Android native libraries are ready"
        inputs.dir(androidJniLibs)
        inputs.property("androidJniLibsRoot", androidJniLibs)
        doLast {
            val root = File(inputs.properties.getValue("androidJniLibsRoot").toString())
            val expected =
                listOf(
                    "arm64-v8a/libcomposetty-ghostty.so",
                    "x86_64/libcomposetty-ghostty.so",
                )
            val missing = expected.filterNot { root.resolve(it).isFile }
            check(missing.isEmpty()) {
                "Missing Android native resources:\n${missing.joinToString("\n")}"
            }
        }
    }

val verifyIosNativeResources =
    tasks.register("verifyIosNativeResources") {
        group = "verification"
        description = "Verifies that all supported iOS native libraries are ready"
        enabled = iosEnabled
        inputs.dir(iosNativeResources)
        inputs.property("iosNativeRoot", iosNativeResources)
        doLast {
            val root = File(inputs.properties.getValue("iosNativeRoot").toString())
            val expected =
                listOf(
                    "iosArm64/lib/libcomposetty-ghostty.a",
                    "iosSimulatorArm64/lib/libcomposetty-ghostty.a",
                )
            val missing = expected.filterNot { root.resolve(it).isFile }
            check(missing.isEmpty()) {
                "Missing iOS native resources:\n${missing.joinToString("\n")}"
            }
        }
    }

tasks.named("check") {
    dependsOn(
        verifyAndroidNativeResources,
        verifyIosNativeResources,
        verifyIosLegalArtifacts,
    )
}

tasks
    .matching { it.name.contains("MavenCentral", ignoreCase = true) }
    .configureEach {
        dependsOn(
            verifyReleaseVersion,
            verifyReleaseNativeResources,
            verifyAndroidNativeResources,
            verifyIosNativeResources,
            verifyIosLegalArtifacts,
        )
    }

tasks
    .matching { it.name == "publishToMavenCentral" }
    .configureEach {
        dependsOn(releasePreflight, confirmMavenCentralUpload)
    }

tasks.register("releaseUpload") {
    group = "publishing"
    description = "Runs the release preflight and uploads a user-managed Central deployment"
    dependsOn("publishToMavenCentral")
}

publishing {
    repositories {
        maven {
            name = "consumerTest"
            url = layout.buildDirectory.dir("consumer-repository").get().asFile.toURI()
        }
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    pom {
        developers {
            developer {
                id.set("befrvnk")
                name.set("Frank Hermann")
                email.set("hermann.frank@gmail.com")
                url.set("https://github.com/befrvnk")
                organization.set("Composetty")
                organizationUrl.set("https://github.com/befrvnk/composetty")
            }
        }
    }
}

tasks
    .matching { it.name == "publishAndReleaseToMavenCentral" }
    .configureEach {
        setDependsOn(emptyList<Any>())
        doFirst {
            error(
                "Automatic Maven Central release is disabled. Use releaseUpload, inspect the " +
                    "user-managed deployment, and publish it manually in the Central Portal."
            )
        }
    }
