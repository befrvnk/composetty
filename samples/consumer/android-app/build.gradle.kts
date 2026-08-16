plugins { id("com.android.application") }

android {
    namespace = "dev.befrvnk.composetty.consumer.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.befrvnk.composetty.consumer.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
}

dependencies {
    val composettyVersion = providers.gradleProperty("composettyVersion").get()
    implementation("dev.befrvnk.composetty:ghostty-compose:$composettyVersion")
}
