package dev.skynet.protocol.runner;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Un repositorio git que el runner encontró en sus carpetas raíz ({@code SKYNET_REPO_ROOTS}), con
 * lo que se puede deducir de él sin ejecutar nada más que git (IP-A). Lo que no se pudo deducir
 * viene a {@code null}.
 *
 * @param path ruta absoluta real del repositorio en la máquina del runner
 * @param name nombre del repositorio: el de su carpeta
 * @param projectName nombre del proyecto según su manifiesto, o el de la carpeta
 * @param projectNameSource de dónde sale {@code projectName}: {@code package.json}, {@code
 *     settings.gradle.kts}, {@code pom.xml}, {@code pyproject.toml}, {@code Cargo.toml}, {@code
 *     go.mod} o {@code directory}
 * @param description descripción del manifiesto o el primer párrafo del README
 * @param descriptionSource de dónde sale {@code description}: el manifiesto o el README
 * @param remoteUrl URL del remoto {@code origin} (o del primero), sin credenciales
 * @param defaultBranch rama por defecto
 * @param defaultBranchSource {@code origin/HEAD}, {@code current} (la rama actual) o {@code
 *     default} ({@code main} a falta de otra)
 * @param tool herramienta de construcción: {@code gradle}, {@code maven}, {@code npm}, {@code
 *     pnpm}, {@code yarn}, {@code python}, {@code cargo} o {@code go}
 * @param validationCommand comando de validación sugerido
 * @param testReportPaths informes JUnit sugeridos
 * @param protectedPaths rutas protegidas sugeridas
 * @param lastCommit hash del último commit
 * @param lastCommitSubject asunto del último commit
 * @param lastCommitAt fecha del último commit
 * @param dirty si hay cambios sin commitear
 * @param agentInstructions si tiene {@code CLAUDE.md} o {@code AGENTS.md}
 */
public record DiscoveredRepository(
    String path,
    String name,
    String projectName,
    String projectNameSource,
    String description,
    String descriptionSource,
    String remoteUrl,
    String defaultBranch,
    String defaultBranchSource,
    String tool,
    String validationCommand,
    List<String> testReportPaths,
    List<String> protectedPaths,
    String lastCommit,
    String lastCommitSubject,
    Instant lastCommitAt,
    boolean dirty,
    boolean agentInstructions) {

  public DiscoveredRepository {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(name, "name");
    testReportPaths = testReportPaths == null ? List.of() : List.copyOf(testReportPaths);
    protectedPaths = protectedPaths == null ? List.of() : List.copyOf(protectedPaths);
  }
}
