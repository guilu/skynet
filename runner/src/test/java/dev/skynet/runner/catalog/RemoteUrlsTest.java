package dev.skynet.runner.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RemoteUrlsTest {

  @Test
  void removesCredentials() {
    assertThat(RemoteUrls.withoutCredentials("https://x-access-token:ghs_abc@github.com/g/r.git"))
        .isEqualTo("https://github.com/g/r.git");
    assertThat(RemoteUrls.withoutCredentials("HTTPS://ghp_abc@github.com/g/r"))
        .isEqualTo("HTTPS://github.com/g/r");
    assertThat(RemoteUrls.withoutCredentials("ssh://git:secreto@host:22/g/r.git"))
        .isEqualTo("ssh://git@host:22/g/r.git");
  }

  @Test
  void keepsWhatHasNone() {
    assertThat(RemoteUrls.withoutCredentials("git@github.com:guilu/forma.git"))
        .isEqualTo("git@github.com:guilu/forma.git");
    assertThat(RemoteUrls.withoutCredentials("ssh://git@host/g/r.git"))
        .isEqualTo("ssh://git@host/g/r.git");
    assertThat(RemoteUrls.withoutCredentials("/home/diego/code/upstream"))
        .isEqualTo("/home/diego/code/upstream");
    assertThat(RemoteUrls.withoutCredentials("  ")).isNull();
  }
}
