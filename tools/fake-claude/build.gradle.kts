plugins {
    id("skynet.java-conventions")
    application
}

dependencies {
    implementation("tools.jackson.core:jackson-databind")
}

application {
    mainClass = "dev.skynet.fakeclaude.FakeClaude"
    applicationName = "fake-claude"
}

// Los fixtures grabados viven en /fixtures/claude y se empaquetan en el jar.
sourceSets {
    main {
        resources {
            srcDir(rootProject.layout.projectDirectory.dir("fixtures"))
            include("claude/**")
        }
    }
}

// fake-codex y fake-agy viven en el mismo módulo: otros lanzadores en bin/, con sus fixtures de
// /fixtures/codex y /fixtures/agy.
sourceSets {
    main {
        resources {
            include("codex/**")
            include("agy/**")
        }
    }
}

val fakeCodexScripts by tasks.registering(CreateStartScripts::class) {
    mainClass = "dev.skynet.fakeclaude.FakeCodex"
    applicationName = "fake-codex"
    outputDir = layout.buildDirectory.dir("fake-codex-scripts").get().asFile
    classpath = tasks.named<CreateStartScripts>("startScripts").get().classpath
}

val fakeAgyScripts by tasks.registering(CreateStartScripts::class) {
    mainClass = "dev.skynet.fakeclaude.FakeAgy"
    applicationName = "fake-agy"
    outputDir = layout.buildDirectory.dir("fake-agy-scripts").get().asFile
    classpath = tasks.named<CreateStartScripts>("startScripts").get().classpath
}

distributions {
    main {
        contents {
            from(fakeCodexScripts) { into("bin") }
            from(fakeAgyScripts) { into("bin") }
        }
    }
}
