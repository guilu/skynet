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
