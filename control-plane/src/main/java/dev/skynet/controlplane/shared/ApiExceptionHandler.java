package dev.skynet.controlplane.shared;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Traduce las excepciones de dominio a respuestas RFC 9457 (ProblemDetail). */
@RestControllerAdvice
class ApiExceptionHandler {

  @ExceptionHandler(NotFoundException.class)
  ProblemDetail notFound(NotFoundException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
  }

  @ExceptionHandler(ConflictException.class)
  ProblemDetail conflict(ConflictException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
  }

  @ExceptionHandler(DuplicateKeyException.class)
  ProblemDetail duplicate(DuplicateKeyException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "El recurso ya existe");
  }

  @ExceptionHandler(OptimisticLockingFailureException.class)
  ProblemDetail staleState(OptimisticLockingFailureException e) {
    return ProblemDetail.forStatusAndDetail(
        HttpStatus.CONFLICT, "El recurso ha cambiado mientras se procesaba; vuelve a intentarlo");
  }
}
