package dev.skynet.controlplane.runner;

import dev.skynet.controlplane.shared.InvalidRequestException;
import dev.skynet.protocol.runner.EventBatch;
import dev.skynet.protocol.runner.ProviderInfo;
import dev.skynet.protocol.runner.RunnerHeartbeat;
import dev.skynet.protocol.runner.RunnerRegistration;
import java.util.List;

/**
 * Lo que acepta la API del runner. Los runners legítimos quedan muy lejos de estos límites (envían
 * los eventos de 100 en 100 y declaran unos pocos proveedores); sirven para que un cuerpo enorme o
 * absurdo no llegue a la base de datos ni a las vistas.
 */
final class RunnerLimits {

  static final int MAX_NAME = 100;
  static final int MAX_VERSION = 200;
  static final int MAX_CAPACITY = 100;
  static final int MAX_PROVIDERS = 20;
  static final int MAX_PROVIDER_ID = 50;
  static final int MAX_RUNNING = 1_000;
  static final int MAX_QUOTAS = 200;
  static final int MAX_EVENTS = 1_000;

  private RunnerLimits() {}

  static void check(RunnerRegistration registration) {
    String name = registration.name();
    if (name.isBlank()
        || name.length() > MAX_NAME
        || name.codePoints().anyMatch(Character::isISOControl)) {
      throw new InvalidRequestException(
          "El nombre del runner tiene que tener entre 1 y "
              + MAX_NAME
              + " caracteres, sin saltos de línea ni caracteres de control");
    }
    capacity(registration.capacity());
    version("runnerVersion", registration.runnerVersion());
    version("providerVersion", registration.providerVersion());
    providers(registration.providers());
  }

  static void check(RunnerHeartbeat heartbeat) {
    capacity(heartbeat.capacity());
    providers(heartbeat.providers());
    if (heartbeat.runningAgentRunIds().size() > MAX_RUNNING) {
      throw new InvalidRequestException(
          "Un latido declara como mucho " + MAX_RUNNING + " invocaciones en curso");
    }
    if (heartbeat.quotas().size() > MAX_QUOTAS) {
      throw new InvalidRequestException(
          "Un latido lleva como mucho " + MAX_QUOTAS + " muestras de cuota");
    }
  }

  static void check(EventBatch batch) {
    if (batch.events().size() > MAX_EVENTS) {
      throw new InvalidRequestException(
          "Un lote lleva como mucho " + MAX_EVENTS + " eventos; parte el envío");
    }
  }

  private static void capacity(int capacity) {
    if (capacity < 1 || capacity > MAX_CAPACITY) {
      throw new InvalidRequestException(
          "La capacidad tiene que estar entre 1 y " + MAX_CAPACITY + ": " + capacity);
    }
  }

  private static void version(String field, String value) {
    if (value != null && value.length() > MAX_VERSION) {
      throw new InvalidRequestException(
          "`" + field + "` ocupa más de " + MAX_VERSION + " caracteres");
    }
  }

  private static void providers(List<ProviderInfo> providers) {
    if (providers == null) {
      return;
    }
    if (providers.size() > MAX_PROVIDERS) {
      throw new InvalidRequestException(
          "Un runner declara como mucho " + MAX_PROVIDERS + " proveedores");
    }
    for (ProviderInfo provider : providers) {
      if (provider.id() == null
          || provider.id().isBlank()
          || provider.id().length() > MAX_PROVIDER_ID) {
        throw new InvalidRequestException(
            "El id de un proveedor tiene que tener entre 1 y " + MAX_PROVIDER_ID + " caracteres");
      }
      version("providers[].version", provider.version());
    }
  }
}
