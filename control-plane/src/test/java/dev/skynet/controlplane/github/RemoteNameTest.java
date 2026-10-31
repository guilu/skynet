package dev.skynet.controlplane.github;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RemoteNameTest {

  @ParameterizedTest
  @CsvSource({
    "https://github.com/guilu/demo-repo, github.com, guilu/demo-repo",
    "https://github.com/guilu/demo-repo.git, github.com, guilu/demo-repo",
    "https://github.com/guilu/demo-repo/, github.com, guilu/demo-repo",
    "https://x-access-token@GitHub.com/guilu/demo-repo.git, github.com, guilu/demo-repo",
    "git@github.com:guilu/demo-repo.git, github.com, guilu/demo-repo",
    "ssh://git@github.com:22/guilu/demo-repo.git, github.com, guilu/demo-repo",
    "https://ghe.example.com/equipo/skynet, ghe.example.com, equipo/skynet",
  })
  void readsTheHostAndOwnerSlashName(String url, String host, String fullName) {
    RemoteName name = RemoteName.parse(url).orElseThrow();
    assertThat(name.host()).isEqualTo(host);
    assertThat(name.fullName()).isEqualTo(fullName);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "/home/diego/code/demo-repo",
        "file:///home/diego/code/demo-repo",
        "https://github.com/guilu",
        "https://gitlab.com/grupo/sub/proyecto"
      })
  void ignoresWhatIsNotOwnerSlashName(String url) {
    assertThat(RemoteName.parse(url)).isEmpty();
  }
}
