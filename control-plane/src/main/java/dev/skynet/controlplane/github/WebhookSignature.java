package dev.skynet.controlplane.github;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * La firma {@code X-Hub-Signature-256} de una entrega: {@code sha256=} y el HMAC-SHA256 en
 * hexadecimal del cuerpo exacto, con el secreto del webhook.
 */
final class WebhookSignature {

  static final String PREFIX = "sha256=";

  private WebhookSignature() {}

  static String sign(String secret, byte[] body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return PREFIX + HexFormat.of().formatHex(mac.doFinal(body));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Si {@code header} es la firma de {@code body}, comparada en tiempo constante. */
  static boolean matches(String secret, byte[] body, String header) {
    if (header == null || !header.startsWith(PREFIX)) {
      return false;
    }
    return MessageDigest.isEqual(
        sign(secret, body).getBytes(StandardCharsets.US_ASCII),
        header.strip().toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
  }
}
