package dev.skynet.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SafeIdsTest {

  @Test
  void sessionIdsOfTheThreeClisAreValid() {
    assertThat(SafeIds.isSessionId("8c121a4f-8ae9-4b1d-9d7f-62437f5ed690")).isTrue();
    assertThat(SafeIds.isSessionId("thread_abc123")).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "../../etc/passwd", "a/b", "-resume", "--fork-session", "a b", ".."})
  void aSessionIdThatCouldEscapeOrBeReadAsAnOptionIsNot(String value) {
    assertThat(SafeIds.isSessionId(value)).isFalse();
  }

  @Test
  void commitsAreHexHashes() {
    assertThat(SafeIds.isCommit("1cde33a")).isTrue();
    assertThat(SafeIds.isCommit("0487109977efa7d11d1d6b1d9afbba48c4be23f4e".substring(0, 40)))
        .isTrue();
    assertThat(SafeIds.isCommit("HEAD")).isFalse();
    assertThat(SafeIds.isCommit("--output=/tmp/x")).isFalse();
    assertThat(SafeIds.isCommit(null)).isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = {"main", "develop", "release/1.2", "feature/ARR-12_x", "v1.0"})
  void ordinaryBranchesAreValid(String value) {
    assertThat(SafeIds.isBranch(value)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "-main",
        "--upload-pack=touch /tmp/x",
        "a..b",
        "a b",
        "a~1",
        "a^",
        "a:b",
        "a?",
        "a*",
        "a[b",
        "a\\b",
        "a//b",
        "a@{1}",
        "@",
        ".hidden",
        "a/.b",
        "a.lock",
        "a/",
        "a."
      })
  void branchesGitRejectsOrThatLookLikeOptionsAreNot(String value) {
    assertThat(SafeIds.isBranch(value)).isFalse();
  }
}
