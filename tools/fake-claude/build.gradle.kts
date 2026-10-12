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

// fake-codex vive en el mismo módulo: otro lanzador en bin/, con sus fixtures de /fixtures/codex.
sourceSets {
    main {
        resources {
            include("codex/**")
        }
    }
}

val fakeCodexScripts by tasks.registering(CreateStartScripts::class) {
    mainClass = "dev.skynet.fakeclaude.FakeCodex"
    applicationName = "fake-codex"
    outputDir = layout.buildDirectory.dir("fake-codex-scripts").get().asFile
    classpath = tasks.named<CreateStartScripts>("startScripts").get().classpath
}

distributions {
    main {
        contents {
            from(fakeCodexScripts) { into("bin") }
        }
    }
}
