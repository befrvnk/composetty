pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    versionCatalogs { create("libs") { from(files("../../gradle/libs.versions.toml")) } }
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = file("../../build/consumer-repository").toURI() }
        mavenCentral()
        google()
    }
}

rootProject.name = "composetty-published-consumer"

include(":android-app")
