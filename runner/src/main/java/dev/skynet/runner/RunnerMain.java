package dev.skynet.runner;

/**
 * Punto de entrada del runner local.
 *
 * <p>En M0 solo arranca y comprueba su entorno; el registro, los heartbeats y la ejecución de
 * agentes llegan en M2 (docs/implementation-plan.md §6).
 */
public final class RunnerMain {

  private RunnerMain() {}

  public static void main(String[] args) {
    System.out.println("skynet-runner " + version());
  }

  static String version() {
    String v = RunnerMain.class.getPackage().getImplementationVersion();
    return v != null ? v : "dev";
  }
}
