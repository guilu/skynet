package dev.skynet.controlplane.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** El JWT de la App y la lectura de su clave privada en los dos formatos PEM. */
class AppJwtTest {

  private static final KeyPair KEYS = FakeGitHub.KEYS;

  @Test
  void readsThePkcs1KeyThatGitHubGives() {
    PrivateKey key = AppJwt.readPrivateKey(FakeGitHub.pkcs1Pem(KEYS));
    assertThat(key.getEncoded()).isEqualTo(KEYS.getPrivate().getEncoded());
  }

  @Test
  void readsAPkcs8Key() {
    String pem =
        "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getMimeEncoder().encodeToString(KEYS.getPrivate().getEncoded())
            + "\n-----END PRIVATE KEY-----\n";
    assertThat(AppJwt.readPrivateKey(pem).getEncoded()).isEqualTo(KEYS.getPrivate().getEncoded());
  }

  @Test
  void rejectsWhatIsNotAPrivateKey() {
    assertThatThrownBy(() -> AppJwt.readPrivateKey("hola"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no es una clave privada PEM");
    assertThatThrownBy(
            () ->
                AppJwt.readPrivateKey(
                    "-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no es válida");
  }

  @Test
  void signsAnRs256JwtThatGitHubAccepts() throws Exception {
    Instant now = Instant.parse("2026-10-31T10:00:00Z");
    String jwt = AppJwt.sign("12345", KEYS.getPrivate(), now);

    String[] parts = jwt.split("\\.");
    assertThat(parts).hasSize(3);
    assertThat(decode(parts[0])).isEqualTo("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
    assertThat(decode(parts[1]))
        .isEqualTo(
            "{\"iat\":"
                + (now.getEpochSecond() - 60)
                + ",\"exp\":"
                + (now.getEpochSecond() + 540)
                + ",\"iss\":\"12345\"}");
    Signature verifier = Signature.getInstance("SHA256withRSA");
    verifier.initVerify(KEYS.getPublic());
    verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
    assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
  }

  private static String decode(String part) {
    return new String(Base64.getUrlDecoder().decode(part), StandardCharsets.UTF_8);
  }
}
