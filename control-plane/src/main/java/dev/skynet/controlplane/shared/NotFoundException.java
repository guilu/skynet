package dev.skynet.controlplane.shared;

import java.util.UUID;

/** El recurso solicitado no existe. Se traduce a HTTP 404. */
public class NotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public NotFoundException(String resource, UUID id) {
    super(resource + " " + id + " no existe");
  }
}
