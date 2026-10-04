package dev.skynet.protocol.runner;

import java.util.Objects;
import java.util.UUID;

/** Respuesta al registro: identidad del runner y su token para el resto de peticiones. */
public record RunnerRegistered(UUID runnerId, String token) {

  public RunnerRegistered {
    Objects.requireNonNull(runnerId, "runnerId");
    Objects.requireNonNull(token, "token");
  }
}
