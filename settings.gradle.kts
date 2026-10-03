pluginManagement {
    includeBuild("build-logic")
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "skynet"

include("protocol", "control-plane", "runner", "tools:fake-claude")
