package dev.skynet.controlplane.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Cómo se pide y cómo se lee el resumen de una conversación (CH-F). */
class ChatSummaryTest {

  @Test
  void readsTheFieldsEvenWithMarkdownAndSeveralLines() {
    assertThat(
            ChatSummary.parse(
                """
                **TÍTULO:** Exportar notas en CSV
                **QUÉ:** Se decide exportar las notas en CSV,
                no en Excel.
                Por qué: lo pide secretaría
                """))
        .contains(
            new ChatSummary(
                "Exportar notas en CSV",
                "Se decide exportar las notas en CSV, no en Excel.",
                "lo pide secretaría"));
    assertThat(ChatSummary.parse("TITULO: Sesiones\nQUE: Sesiones de 30 min"))
        .contains(new ChatSummary("Sesiones", "Sesiones de 30 min", null));
  }

  @Test
  void nothingToSaveOrAnAnswerWithoutTheFormatSavesNothing() {
    assertThat(ChatSummary.parse("NADA")).isEmpty();
    assertThat(ChatSummary.parse("  ")).isEmpty();
    assertThat(ChatSummary.parse(null)).isEmpty();
    assertThat(ChatSummary.parse("Hablamos de las sesiones y quedamos en 30 minutos.")).isEmpty();
    assertThat(ChatSummary.parse("TÍTULO: Sin qué")).isEmpty();
  }

  @Test
  void aLongTitleIsCut() {
    String title = "x".repeat(300);
    assertThat(ChatSummary.parse("TÍTULO: " + title + "\nQUÉ: algo").orElseThrow().title())
        .hasSize(ChatSummary.MAX_TITLE)
        .endsWith("…");
  }

  @Test
  void thePromptCarriesTheProjectAndTheLatestOfTheConversation() {
    String old = "a".repeat(ChatSummary.MAX_TRANSCRIPT_CHARS);
    String prompt =
        ChatSummary.prompt(
            "AKA",
            "akademia",
            "Sesiones",
            List.of(
                new SkynyPrompt.Line(MessageAuthor.USER, old),
                new SkynyPrompt.Line(MessageAuthor.USER, "¿Cuánto duran las sesiones?"),
                new SkynyPrompt.Line(MessageAuthor.SKYNY, "30 minutos, según la memoria.")));
    assertThat(prompt)
        .contains("«Sesiones» con una persona del proyecto AKA «akademia»")
        .contains("No uses herramientas.")
        .doesNotContain(old)
        .endsWith(
            "<conversacion>\nPersona: ¿Cuánto duran las sesiones?\n"
                + "Skyny: 30 minutos, según la memoria.\n</conversacion>");
  }
}
