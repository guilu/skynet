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

// Los tests del ejecutor lanzan fake-claude, fake-codex y fake-gemini como procesos reales.
val fakeClaude = project(":tools:fake-claude")
tasks.test {
    dependsOn(fakeClaude.tasks.named("installDist"))
    systemProperty(
        "skynet.fakeClaude",
        fakeClaude.layout.buildDirectory.file("install/fake-claude/bin/fake-claude").get().asFile.path,
    )
    systemProperty(
        "skynet.fakeCodex",
        fakeClaude.layout.buildDirectory.file("install/fake-claude/bin/fake-codex").get().asFile.path,
    )
    systemProperty(
        "skynet.fakeGemini",
        fakeClaude.layout.buildDirectory.file("install/fake-claude/bin/fake-gemini").get().asFile.path,
    )
}
