plugins {
    id("skynet.java-conventions")
    application
}

dependencies {
    implementation(project(":protocol"))
}

application {
    mainClass = "dev.skynet.runner.RunnerMain"
}
