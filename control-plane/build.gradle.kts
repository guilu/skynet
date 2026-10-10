plugins {
    id("skynet.java-conventions")
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":protocol"))
    implementation(platform(libs.spring.modulith.bom))

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    // YAML de los workflows (W1), con las posiciones de cada nodo para los errores.
    implementation("org.yaml:snakeyaml")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation(platform(libs.spring.modulith.bom))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
}

tasks.bootJar {
    archiveFileName = "control-plane.jar"
}

// Las pruebas de extremo a extremo arrancan un runner real en el mismo proceso, con fake-claude
// como agente.
dependencies {
    testImplementation(project(":runner"))
}

val fakeClaude = project(":tools:fake-claude")
// Contratos JSON entre el backend y la web (ContractIT y web/src/contracts.test.ts).
val contracts = rootProject.layout.projectDirectory.dir("fixtures/contracts")
tasks.test {
    dependsOn(fakeClaude.tasks.named("installDist"))
    inputs.dir(contracts).withPropertyName("contracts").optional()
    systemProperty("skynet.contracts", contracts.asFile.path)
    System.getProperty("skynet.contracts.update")?.let { systemProperty("skynet.contracts.update", it) }
    systemProperty(
        "skynet.fakeClaude",
        fakeClaude.layout.buildDirectory.file("install/fake-claude/bin/fake-claude").get().asFile.path,
    )
}
