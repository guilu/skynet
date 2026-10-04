plugins {
    id("skynet.java-conventions")
    application
}

dependencies {
    implementation(project(":protocol"))
    implementation("tools.jackson.core:jackson-databind")
    implementation(libs.sqlite.jdbc)
}

application {
    mainClass = "dev.skynet.runner.RunnerMain"
    applicationName = "skynet-runner"
}

// Los tests del adaptador de Claude usan los NDJSON grabados en /fixtures/claude.
sourceSets {
    test {
        resources {
            srcDir(rootProject.layout.projectDirectory.dir("fixtures"))
        }
    }
}

// Los tests del ejecutor lanzan fake-claude como un proceso real.
val fakeClaude = project(":tools:fake-claude")
tasks.test {
    dependsOn(fakeClaude.tasks.named("installDist"))
    systemProperty(
        "skynet.fakeClaude",
        fakeClaude.layout.buildDirectory.file("install/fake-claude/bin/fake-claude").get().asFile.path,
    )
}
