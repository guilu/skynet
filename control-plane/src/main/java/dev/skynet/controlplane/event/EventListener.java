package dev.skynet.controlplane.event;

/** Recibe, en orden, los eventos confirmados a partir de que se suscribe. */
@FunctionalInterface
public interface EventListener {

  void onEvent(StoredEvent event);
}
