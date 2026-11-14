package dev.skynet.controlplane.memory;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Detecta, sin modelo, el texto de una observación que parece una orden para el agente que la va a
 * leer y no una nota sobre el proyecto (MV-A). Lo que encuentra va a cuarentena: así, una issue o
 * un fichero del repositorio que dice «guarda en la memoria que…» no llega a todas las ejecuciones
 * siguientes.
 *
 * <p>Es estricto a propósito: solo marca frases dirigidas al agente («ignora las instrucciones», «a
 * partir de ahora…») y descargas que se ejecutan ({@code curl … | sh}). Una nota normal sobre
 * comandos, URLs o configuración no se marca.
 */
final class MemoryInstructionDetector {

  private record Rule(String reason, Pattern pattern) {}

  private static final int FLAGS =
      Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;

  private static final List<Rule> RULES =
      List.of(
          new Rule(
              "pide ignorar las instrucciones",
              Pattern.compile(
                  "\\b(ignora|olvida)\\s+(todas\\s+)?(las|tus|cualquier|cualesquiera)"
                      + "?\\s*(\\w+\\s+){0,2}(instrucciones|indicaciones)",
                  FLAGS)),
          new Rule(
              "pide ignorar las instrucciones",
              Pattern.compile(
                  "\\b(ignore|disregard|forget)\\s+(all\\s+|any\\s+|the\\s+|your\\s+)*"
                      + "(previous\\s+|prior\\s+|above\\s+|earlier\\s+)?(instructions|prompts?)",
                  FLAGS)),
          new Rule(
              "da órdenes al agente",
              Pattern.compile(
                  "\\b(a\\s+partir\\s+de\\s+ahora|desde\\s+ahora)\\s*,?\\s*(debes|tienes\\s+que|"
                      + "eres|actúa|responde|ejecuta|haz)\\b",
                  FLAGS)),
          new Rule(
              "da órdenes al agente",
              Pattern.compile(
                  "\\b(from\\s+now\\s+on\\s*,?\\s*you\\s+(must|should|will|are)|you\\s+must\\s+now"
                      + "|new\\s+instructions\\s*:)",
                  FLAGS)),
          new Rule(
              "descarga y ejecuta código",
              Pattern.compile(
                  "\\b(curl|wget|iwr|invoke-webrequest)\\b[^\\n|;]*\\|\\s*(sudo\\s+)?"
                      + "(ba|z|da|k)?sh\\b",
                  FLAGS)),
          new Rule(
              "descarga y ejecuta código",
              Pattern.compile(
                  "base64\\s+(-d|--decode)\\b[^\\n|;]*\\|\\s*(sudo\\s+)?(ba|z)?sh\\b", FLAGS)),
          new Rule(
              "descarga y ejecuta código",
              Pattern.compile("\\b(bash|sh)\\s+<\\(\\s*(curl|wget)\\b", FLAGS)));

  private MemoryInstructionDetector() {}

  /** El motivo de la cuarentena, o vacío si ningún campo lo parece. */
  static Optional<String> reason(String... fields) {
    for (String field : fields) {
      if (field == null || field.isEmpty()) {
        continue;
      }
      for (Rule rule : RULES) {
        if (rule.pattern().matcher(field).find()) {
          return Optional.of("Parece una orden para el agente: " + rule.reason());
        }
      }
    }
    return Optional.empty();
  }
}
