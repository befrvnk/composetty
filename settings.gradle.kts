pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "composetty"

include(":samples:android")
include(":samples:remote")

if (providers.environmentVariable("COMPOSETTY_IOS_NATIVE").isPresent) {
    include(":samples:ios")
}
