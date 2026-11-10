package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.definition.JudgeSpec;
import dev.skynet.controlplane.definition.JudgeSpec.Criterion;
import dev.skynet.protocol.runner.StartAgent;
import java.util.List;
import java.util.Map;

/**
 * El prompt de un juez (VA-B). Lleva la tarea, de dónde sale el cambio, lo que ya comprobó Skynet
 * (con sus avisos), la rúbrica y la lente. A propósito, no lleva nada de lo que dijo el agente que
 * hizo el cambio (ni su resumen ni su salida estructurada): si el juez lee «he añadido tests para
 * todos los casos», tiende a creérselo.
 */
final class JudgePrompt {

  /** Lo que mira cada lente, además de la rúbrica. */
  static final Map<String, String> LENSES =
      Map.of(
          "correctness",
          "Corrección: ¿hace lo que pide la tarea? Busca errores de lógica, casos límite sin tratar,"
              + " regresiones y tests que no prueban lo que dicen.",
          "security",
          "Seguridad: busca entradas sin validar, inyecciones, secretos en el código, permisos de"
              + " más y datos sensibles en los logs.",
          "performance",
          "Rendimiento: busca trabajo de más, como consultas dentro de bucles, operaciones caras"
              + " repetidas, estructuras de datos que no escalan o bloqueos.",
          "reproduce",
          "Reproducir: intenta reproducir lo que dice la tarea (el bug o el comportamiento pedido)"
              + " con el código y los tests. ¿Queda demostrado que se resuelve?",
          "completeness",
          "Completitud: pregúntate qué falta para que esto esté terminado (tests que no hay,"
              + " documentación, migraciones, casos límite, mensajes de error). Lo que falte es un"
              + " hallazgo.");

  /**
   * Una comprobación de la ronda, como la ve el juez.
   *
   * @param name el check
   * @param passed si pasó
   * @param reason por qué no pasó
   * @param warnings sus avisos
   */
  record Check(String name, boolean passed, String reason, List<String> warnings) {}

  /**
   * Lo que necesita el prompt.
   *
   * @param workItemKey la clave de la tarea
   * @param title su título
   * @param description su descripción, o {@code null}
   * @param baseCommit el commit desde el que se mide el cambio, o {@code null}
   * @param checks lo que comprobó Skynet en esta ronda
   * @param round la ronda, desde 1
   * @param extra las indicaciones del propio agente juez, o {@code null}
   * @param seen lo que ya se ha señalado (RH-B), una línea por hallazgo con su desenlace
   * @param previousHead el commit que vio el juez en la ronda anterior, o {@code null}
   */
  record Context(
      String workItemKey,
      String title,
      String description,
      String baseCommit,
      List<Check> checks,
      int round,
      String extra,
      List<String> seen,
      String previousHead) {

    Context(
        String workItemKey,
        String title,
        String description,
        String baseCommit,
        List<Check> checks,
        int round,
        String extra) {
      this(workItemKey, title, description, baseCommit, checks, round, extra, List.of(), null);
    }
  }

  private JudgePrompt() {}

  /**
   * El prompt de un escéptico (VA-C): solo el hallazgo, de dónde sale el cambio y el worktree. Ni
   * la nota del juez ni lo que dijo el autor: tiene que mirarlo él.
   */
  static String refutation(Context context, JudgeVerdicts.Finding finding) {
    StringBuilder out = new StringBuilder();
    out.append(
        "Eres un escéptico: un revisor ha encontrado un problema que, según él, impide fusionar el"
            + " cambio. Tu trabajo es intentar demostrar que no es un problema real en este código."
            + " Trabajas en una copia del worktree en solo lectura: no cambies nada.\n");
    out.append("\n## La tarea\n\n");
    out.append(context.workItemKey()).append(" - ").append(context.title()).append('\n');
    out.append("\n## El hallazgo\n\n");
    if (finding.location() != null) {
      out.append("En `").append(finding.location()).append("`: ");
    }
    out.append(finding.text()).append('\n');
    out.append("\n## El cambio\n\n");
    if (context.baseCommit() != null) {
      out.append("Míralo con `git diff ")
          .append(context.baseCommit())
          .append("` (y `git status` para lo que no está confirmado). ");
    } else {
      out.append("Míralo con `git diff` y `git log`. ");
    }
    out.append("Lee el código que haga falta, los tests incluidos.\n");
    out.append(StartAgent.REVIEW_MARKER);
    out.append(
        "\n## Qué contestar\n\nRefútalo (`refuted: true`) solo si puedes demostrar con el código"
            + " que no pasa: el caso ya está tratado, no se puede dar, el fichero no es el que dice o"
            + " el hallazgo pide algo que la tarea no pide. Si dudas, o si es verdad aunque sea en"
            + " parte, no lo refutes (`refuted: false`). En `reason`, en una o dos frases, lo que"
            + " has comprobado (con el fichero y la línea si los sabes).");
    return out.toString();
  }

  /**
   * El prompt de un juez del delta (AU-B): el de siempre, con {@code context.baseCommit()} en el
   * commit que ya se aprobó, y una nota para que juzgue solo lo que cambió desde entonces.
   */
  static String delta(Context context, JudgeSpec spec, JudgeSpec.Judge judge, int tier) {
    String prompt = build(context, spec, judge);
    String note =
        "Este cambio ya lo aprobó un juez en el commit `"
            + context.baseCommit()
            + "`. Después se corrigió para que pasara la CI, y lo corregido tiene nivel de riesgo "
            + tier
            + ". Juzga **solo lo que cambió desde ese commit**: que la corrección es buena, que no"
            + " rompe lo que ya estaba bien y que no mete nada que la tarea no pida. Puntúa la"
            + " rúbrica pensando en ese delta.\n\n";
    String marker = "\n## El cambio\n\n";
    int at = prompt.indexOf(marker);
    return at < 0
        ? note + prompt
        : prompt.substring(0, at + marker.length()) + note + prompt.substring(at + marker.length());
  }

  static String build(Context context, JudgeSpec spec, JudgeSpec.Judge judge) {
    StringBuilder out = new StringBuilder();
    out.append(
        "Eres un juez independiente: revisa el cambio que se ha hecho para la tarea y puntúalo con"
            + " la rúbrica. Trabajas en una copia del worktree en solo lectura: no cambies nada.\n");
    out.append("\n## La tarea\n\n");
    out.append(context.workItemKey()).append(" - ").append(context.title()).append('\n');
    if (context.description() != null && !context.description().isBlank()) {
      out.append('\n').append(context.description().strip()).append('\n');
    }
    out.append("\n## El cambio\n\n");
    if (context.baseCommit() != null) {
      out.append("Es lo que hay en el worktree desde el commit `")
          .append(context.baseCommit())
          .append("`: míralo con `git diff ")
          .append(context.baseCommit())
          .append("` (y `git status` para lo que no está confirmado). ");
    } else {
      out.append("Es lo que hay en el worktree: míralo con `git diff` y `git log`. ");
    }
    out.append(
        "Lee los ficheros que necesites. Juzga el código, no lo que digan los commits o los"
            + " comentarios.\n");
    if (context.round() > 1) {
      out.append("\nEs la ronda ")
          .append(context.round())
          .append(
              ": el agente ha corregido lo que se encontró en la anterior. Juzga cómo está"
                  + " ahora.");
      if (context.previousHead() != null) {
        out.append(" Mira primero `git diff ")
            .append(context.previousHead())
            .append("`: es lo que se corrigió desde la ronda anterior. Después puntúa el cambio")
            .append(" entero, como siempre.");
      }
      out.append('\n');
    }
    if (context.seen() != null && !context.seen().isEmpty()) {
      out.append("\n## Lo que ya se ha señalado\n\n");
      out.append(
          "Esto ya lo dijo un juez, con lo que pasó después. No vuelvas a señalar lo refutado, lo"
              + " intencionado ni lo que no aplica, salvo que el código haya cambiado y ahora sí sea"
              + " un problema. Lo que sigue sin corregir, compruébalo: si sigue ahí, señálalo otra"
              + " vez.\n\n");
      context.seen().forEach(line -> out.append(line).append('\n'));
    }
    out.append("\n## Lo que ya ha comprobado Skynet\n\n");
    if (context.checks().isEmpty()) {
      out.append("Esta fase no tiene comprobaciones automáticas.\n");
    } else {
      for (Check check : context.checks()) {
        out.append("- `").append(check.name()).append("`: ");
        out.append(check.passed() ? "pasa" : "no pasa");
        if (!check.passed() && check.reason() != null) {
          out.append(" (").append(check.reason()).append(')');
        }
        out.append('\n');
        for (String warning : check.warnings()) {
          out.append("  - Aviso: ").append(warning).append('\n');
        }
      }
    }
    if (judge.lens() != null) {
      out.append("\n## Tu lente\n\n").append(LENSES.get(judge.lens())).append('\n');
    }
    out.append("\n## La rúbrica\n\n");
    if (spec.rubricFile() != null) {
      out.append("Está en `")
          .append(spec.rubricFile())
          .append(
              "`: léela y puntúa cada criterio que diga, con su id, su peso (1 si no lo dice) y"
                  + " si es obligatorio.\n");
    } else {
      out.append(
          "Puntúa cada criterio de 1 (muy mal) a 5 (perfecto), con una justificación corta:\n\n");
      for (Criterion c : spec.criteria()) {
        out.append("- `")
            .append(c.id())
            .append("` (peso ")
            .append(c.weight())
            .append(c.required() ? ", obligatorio" : "")
            .append("): ")
            .append(c.text())
            .append('\n');
      }
    }
    out.append(
        "\nAprueba con una media ponderada de "
            + JudgeVerdicts.format(spec.threshold())
            + " o más, sin ningún criterio obligatorio por debajo de "
            + JudgeSpec.REQUIRED_MINIMUM
            + " y sin nada que bloquee.\n");
    out.append(StartAgent.REVIEW_MARKER);
    out.append("\n## Los hallazgos\n\n");
    out.append(
        "Apunta cada problema concreto con su fichero, la línea si la sabes, su gravedad"
            + " (`blocking` si hay que arreglarlo antes de fusionar, `minor` si no) y por qué. Si no"
            + " hay ninguno, deja la lista vacía. No inventes problemas para justificar una nota.\n");
    if (context.extra() != null && !context.extra().isBlank()) {
      out.append("\n## Indicaciones\n\n").append(context.extra().strip()).append('\n');
    }
    out.append(
        "\nDevuelve la salida estructurada: `criteria` (id, score y reason de cada criterio),"
            + " `findings` y `summary` (dos o tres frases).");
    return out.toString();
  }
}
