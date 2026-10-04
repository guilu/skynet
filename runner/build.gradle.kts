plugins {
    id("skynet.java-conventions")
    application
}

dependencies {
    implementation(project(":protocol"))
    implementation("tools.jackson.core:jackson-databind")
}

application {
    mainClass = "dev.skynet.runner.RunnerMain"
}

// Los tests del adaptador de Claude usan los NDJSON grabados en /fixtures/claude.
sourceSets {
    test {
        resources {
            srcDir(rootProject.layout.projectDirectory.dir("fixtures"))
        }
    }
}
