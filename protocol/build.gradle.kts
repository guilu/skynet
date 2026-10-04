plugins {
    id("skynet.java-conventions")
    `java-library`
}

dependencies {
    // Solo para comprobar que los mensajes se serializan con el mismo Jackson que usan los dos lados.
    testImplementation("tools.jackson.core:jackson-databind")
}
