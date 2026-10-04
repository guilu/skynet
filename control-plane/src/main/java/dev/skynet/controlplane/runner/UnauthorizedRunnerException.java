package dev.skynet.controlplane.runner;

/** Token de runner o de registro ausente o inválido. Se traduce a HTTP 401. */
class UnauthorizedRunnerException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  UnauthorizedRunnerException(String message) {
    super(message);
  }
}
