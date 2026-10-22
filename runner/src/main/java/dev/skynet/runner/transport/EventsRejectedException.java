package dev.skynet.runner.transport;

import java.io.IOException;

/** El control plane rechazó un lote de eventos por algo que no cambiará al reintentarlo. */
public class EventsRejectedException extends IOException {

  private static final long serialVersionUID = 1L;

  public EventsRejectedException(String message) {
    super(message);
  }
}
