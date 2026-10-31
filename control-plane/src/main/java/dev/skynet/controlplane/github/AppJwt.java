package dev.skynet.controlplane.github;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * El JWT con el que la App se identifica ante GitHub (RS256, firmado con su clave privada). Solo
 * sirve para pedir tokens de instalación y para {@code GET /app}; GitHub no admite más de 10
 * minutos de vida.
 */
final class AppJwt {

  /** Vida del JWT. */
  static final Duration LIFETIME = Duration.ofMinutes(9);

  /** Margen hacia atrás por si el reloj de este equipo va adelantado respecto al de GitHub. */
  static final Duration CLOCK_SKEW = Duration.ofSeconds(60);

  private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();
  private static final byte[] RSA_ENCRYPTION_OID = {
    0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01
  };

  private AppJwt() {}

  static String sign(String appId, PrivateKey key, Instant now) {
    String header = encode("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
    String claims =
        encode(
            "{\"iat\":"
                + now.minus(CLOCK_SKEW).getEpochSecond()
                + ",\"exp\":"
                + now.plus(LIFETIME).getEpochSecond()
                + ",\"iss\":\""
                + appId
                + "\"}");
    String content = header + "." + claims;
    try {
      Signature signature = Signature.getInstance("SHA256withRSA");
      signature.initSign(key);
      signature.update(content.getBytes(StandardCharsets.US_ASCII));
      return content + "." + URL.encodeToString(signature.sign());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("No se puede firmar el JWT de la App: " + e.getMessage(), e);
    }
  }

  /**
   * Lee la clave privada de un {@code .pem}: la que da GitHub es PKCS#1 ({@code BEGIN RSA PRIVATE
   * KEY}); también vale PKCS#8 ({@code BEGIN PRIVATE KEY}), p. ej. tras pasarla por openssl.
   *
   * @throws IllegalArgumentException si no es una clave RSA en uno de esos dos formatos
   */
  static PrivateKey readPrivateKey(String pem) {
    boolean pkcs1 = pem.contains("-----BEGIN RSA PRIVATE KEY-----");
    if (!pkcs1 && !pem.contains("-----BEGIN PRIVATE KEY-----")) {
      throw new IllegalArgumentException(
          "no es una clave privada PEM (BEGIN RSA PRIVATE KEY o BEGIN PRIVATE KEY)");
    }
    String body =
        pem.replaceAll("-----(BEGIN|END) (RSA )?PRIVATE KEY-----", "").replaceAll("\\s", "");
    try {
      byte[] der = Base64.getDecoder().decode(body);
      return KeyFactory.getInstance("RSA")
          .generatePrivate(new PKCS8EncodedKeySpec(pkcs1 ? wrapPkcs1(der) : der));
    } catch (IllegalArgumentException | GeneralSecurityException e) {
      throw new IllegalArgumentException("la clave privada no es válida: " + e.getMessage(), e);
    }
  }

  /**
   * Envuelve una clave PKCS#1 en la estructura PKCS#8 que entiende Java, sin dependencias: {@code
   * SEQUENCE { INTEGER 0, SEQUENCE { rsaEncryption, NULL }, OCTET STRING pkcs1 }}.
   */
  static byte[] wrapPkcs1(byte[] pkcs1) {
    ByteArrayOutputStream algorithm = new ByteArrayOutputStream();
    algorithm.writeBytes(RSA_ENCRYPTION_OID);
    algorithm.writeBytes(new byte[] {0x05, 0x00});
    ByteArrayOutputStream content = new ByteArrayOutputStream();
    content.writeBytes(new byte[] {0x02, 0x01, 0x00});
    content.writeBytes(der(0x30, algorithm.toByteArray()));
    content.writeBytes(der(0x04, pkcs1));
    return der(0x30, content.toByteArray());
  }

  private static byte[] der(int tag, byte[] value) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(tag);
    int length = value.length;
    if (length < 0x80) {
      out.write(length);
    } else {
      int bytes = (Integer.SIZE - Integer.numberOfLeadingZeros(length) + 7) / 8;
      out.write(0x80 | bytes);
      for (int i = bytes - 1; i >= 0; i--) {
        out.write(length >>> (8 * i));
      }
    }
    out.writeBytes(value);
    return out.toByteArray();
  }

  private static String encode(String json) {
    return URL.encodeToString(json.getBytes(StandardCharsets.UTF_8));
  }
}
