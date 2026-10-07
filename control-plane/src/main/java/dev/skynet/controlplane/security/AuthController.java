package dev.skynet.controlplane.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Login y sesión de la web. La salida es {@code POST /api/auth/logout}, de Spring Security. */
@RestController
@RequestMapping("/api/auth")
class AuthController {

  record Credentials(@NotBlank String username, @NotBlank String password) {}

  record Session(String username) {}

  private final AuthenticationManager authentication;
  private final SecurityContextRepository contexts;

  AuthController(AuthenticationManager authentication, SecurityContextRepository contexts) {
    this.authentication = authentication;
    this.contexts = contexts;
  }

  /** Usuario de la sesión actual; 401 si no hay sesión. */
  @GetMapping("/session")
  Session session(Principal principal) {
    return new Session(principal.getName());
  }

  @PostMapping("/login")
  Session login(
      @Valid @RequestBody Credentials credentials,
      HttpServletRequest request,
      HttpServletResponse response) {
    Authentication user =
        authentication.authenticate(
            UsernamePasswordAuthenticationToken.unauthenticated(
                credentials.username(), credentials.password()));
    // Sesión nueva tras el login: un identificador de sesión anterior no sirve para entrar.
    if (request.getSession(false) != null) {
      request.changeSessionId();
    }
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(user);
    SecurityContextHolder.setContext(context);
    contexts.saveContext(context, request, response);
    return new Session(user.getName());
  }

  @ExceptionHandler(AuthenticationException.class)
  ProblemDetail badCredentials() {
    return ProblemDetail.forStatusAndDetail(
        HttpStatus.UNAUTHORIZED, "Usuario o contraseña incorrectos");
  }
}
