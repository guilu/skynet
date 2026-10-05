package dev.skynet.controlplane.shared;

import static dev.skynet.controlplane.shared.PayloadRedactor.MASK;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

class PayloadRedactorTest {

  private final PayloadRedactor redactor =
      new PayloadRedactor(
          new RedactionProperties(List.of("MY_SECRET", "SHORT", "MISSING")),
          new MockEnvironment()
              .withProperty("MY_SECRET", "s3cr3t-value-from-env")
              .withProperty("SHORT", "abc"));

  @ParameterizedTest
  @ValueSource(
      strings = {
        "sk-ant-api03-AbCdEfGhIjKlMnOpQrStUvWxYz0123456789",
        "sk-proj-AbCdEfGhIjKlMnOpQrStUvWxYz0123",
        "ghp_AbCdEfGhIjKlMnOpQrStUvWxYz0123456789",
        "github_pat_11AAAAAAA0123456789_abcdefghijklmnop",
        "AKIAIOSFODNN7EXAMPLE",
        "xoxb-1234567890-abcdefghij",
        "AIzaSyA1234567890abcdefghijklmnopqrstuv",
        "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U",
        "s3cr3t-value-from-env"
      })
  void knownTokenFormatsAndSecretEnvValuesAreMasked(String secret) {
    assertThat(redactor.redact("antes " + secret + " después"))
        .isEqualTo("antes " + MASK + " después");
  }

  @Test
  void assignmentsHeadersAndUrlsKeepTheirNameButNotTheValue() {
    assertThat(redactor.redact("export DB_PASSWORD=hunter2hunter2"))
        .isEqualTo("export DB_PASSWORD=" + MASK);
    assertThat(redactor.redact("{\"api_key\": \"abcdef123456\"}"))
        .isEqualTo("{\"api_key\": \"" + MASK + "\"}");
    assertThat(redactor.redact("curl -H 'Authorization: Bearer abc.def-ghi_jkl012345'"))
        .isEqualTo("curl -H 'Authorization: Bearer " + MASK + "'");
    assertThat(redactor.redact("git clone https://user:p4ssw0rd@github.com/x/y.git"))
        .isEqualTo("git clone https://user:" + MASK + "@github.com/x/y.git");
    assertThat(
            redactor.redact(
                "-----BEGIN RSA PRIVATE KEY-----\nMIIEow\nabc\n-----END RSA PRIVATE KEY-----"))
        .isEqualTo(MASK);
  }

  @Test
  void ordinaryTextIsLeftAlone() {
    String text =
        "Edité calc.py: return a + b. Tokens de entrada: 1234. El test pasa (token count ok)."
            + " Short env value abc stays.";
    assertThat(redactor.redact(text)).isEqualTo(text);
  }

  @Test
  void nestedPayloadsAreRedactedWithoutTouchingTheOriginal() {
    Map<String, Object> payload =
        Map.of(
            "toolName",
            "Bash",
            "input",
            Map.of("command", "echo sk-ant-api03-AbCdEfGhIjKlMnOpQrStUvWxYz0123456789"),
            "env",
            Map.of("GITHUB_TOKEN", "whatever-value", "PATH", "/usr/bin"),
            "outputs",
            List.of("ok", "password: correcthorse"),
            "usage",
            Map.of("input", 10, "output", 20),
            "isError",
            false);

    Map<String, Object> redacted = redactor.redact(payload);

    assertThat(redacted)
        .containsEntry("toolName", "Bash")
        .containsEntry("input", Map.of("command", "echo " + MASK))
        .containsEntry("env", Map.of("GITHUB_TOKEN", MASK, "PATH", "/usr/bin"))
        .containsEntry("outputs", List.of("ok", "password: " + MASK))
        .containsEntry("usage", Map.of("input", 10, "output", 20))
        .containsEntry("isError", false);
    assertThat(payload.get("input").toString()).contains("sk-ant-");
  }
}
