package dev.skynet.controlplane.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(OutputCaptureExtension.class)
class SecurityConfigurationTest {

  private final PasswordEncoder encoder =
      PasswordEncoderFactories.createDelegatingPasswordEncoder();

  @TempDir Path dir;

  @Test
  void aGeneratedPasswordIsNeverWrittenToTheLog(CapturedOutput output) {
    Path file = dir.resolve("data/admin-password");
    UserDetailsService users =
        new SecurityConfiguration()
            .users(
                new SecurityProperties("admin", "", false, List.of(), file, null, null), encoder);

    String hash = users.loadUserByUsername("admin").getPassword();
    assertThat(Arrays.stream(output.getAll().split("[\\s'\":,]+")))
        .noneMatch(word -> !word.isBlank() && encoder.matches(word, hash));
    assertThat(output.getAll()).contains(file.toString());
  }

  @Test
  void theGeneratedPasswordIsInAFileOnlyItsOwnerCanRead() throws Exception {
    Path file = dir.resolve("data/admin-password");
    Files.createDirectories(file.getParent());
    Files.writeString(file, "anterior");
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));

    UserDetailsService users =
        new SecurityConfiguration()
            .users(
                new SecurityProperties("admin", null, false, List.of(), file, null, null), encoder);

    assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        .isEqualTo("rw-------");
    String password = Files.readString(file).strip();
    assertThat(encoder.matches(password, users.loadUserByUsername("admin").getPassword())).isTrue();
  }

  @Test
  void aConfiguredPasswordWritesNoFile() {
    Path file = dir.resolve("admin-password");
    new SecurityConfiguration()
        .users(
            new SecurityProperties("admin", "secreto", false, List.of(), file, null, null),
            encoder);

    assertThat(file).doesNotExist();
  }
}
