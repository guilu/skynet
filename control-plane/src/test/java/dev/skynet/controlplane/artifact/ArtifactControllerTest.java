package dev.skynet.controlplane.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ArtifactControllerTest {

  @Test
  void theDownloadNameIsTheSanitizedBaseName() {
    assertThat(ArtifactController.contentDisposition("docs/skynet/mapa.md"))
        .isEqualTo("attachment; filename=\"mapa.md\"; filename*=UTF-8''mapa.md");
    assertThat(ArtifactController.contentDisposition("informes/resumen técnico.pdf"))
        .isEqualTo(
            "attachment; filename=\"resumen_t_cnico.pdf\";"
                + " filename*=UTF-8''resumen%20t%C3%A9cnico.pdf");
  }

  @Test
  void quotesSeparatorsAndControlCharactersNeverReachTheHeader() {
    assertThat(ArtifactController.contentDisposition("a\"b;c\r\nSet-Cookie: x.md"))
        .isEqualTo(
            "attachment; filename=\"abcSet-Cookie_x.md\"; filename*=UTF-8''abcSet-Cookie%20x.md");
    assertThat(ArtifactController.contentDisposition("..\\..\\secreto.txt"))
        .isEqualTo("attachment; filename=\"secreto.txt\"; filename*=UTF-8''secreto.txt");
    assertThat(ArtifactController.contentDisposition("docs/.."))
        .isEqualTo("attachment; filename=\"artefacto\"; filename*=UTF-8''artefacto");
    assertThat(ArtifactController.contentDisposition("docs/"))
        .isEqualTo("attachment; filename=\"artefacto\"; filename*=UTF-8''artefacto");
  }

  @Test
  void onlyImagesAndPdfKeepTheirTypeWhenDownloaded() {
    assertThat(ArtifactController.downloadType("image/png")).hasToString("image/png");
    assertThat(ArtifactController.downloadType("application/pdf")).hasToString("application/pdf");
    assertThat(ArtifactController.downloadType("text/markdown"))
        .hasToString("text/plain;charset=UTF-8");
    assertThat(ArtifactController.downloadType("text/html"))
        .hasToString("text/plain;charset=UTF-8");
    assertThat(ArtifactController.downloadType("application/json")).hasToString("application/json");
    assertThat(ArtifactController.downloadType("image/svg+xml"))
        .hasToString("application/octet-stream");
    assertThat(ArtifactController.downloadType("no es un tipo"))
        .hasToString("application/octet-stream");
  }
}
