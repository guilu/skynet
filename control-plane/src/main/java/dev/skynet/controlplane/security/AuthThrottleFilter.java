package dev.skynet.controlplane.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Frena a quien prueba contraseñas: el login, cualquier petición con HTTP Basic y el registro del
 * runner. Un 401 cuenta como fallo; tras demasiados, el origen recibe 429 con {@code Retry-After}
 * hasta que pase el bloqueo. La web (login y Basic) y el registro del runner se cuentan aparte. El
 * origen es {@code getRemoteAddr()}: detrás de un proxy, con {@code
 * server.forward-headers-strategy} pasa a ser el cliente real.
 */
final class AuthThrottleFilter extends OncePerRequestFilter {

  private final AuthThrottle throttle;

  AuthThrottleFilter(AuthThrottle throttle) {
    this.throttle = throttle;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String scope = scope(request);
    if (scope == null) {
      chain.doFilter(request, response);
      return;
    }
    String key = scope + ":" + request.getRemoteAddr();
    Optional<Duration> wait = throttle.blocked(key);
    if (wait.isPresent()) {
      long seconds = Math.max(1, (wait.get().toMillis() + 999) / 1000);
      response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
      response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
      response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
      response.setCharacterEncoding("UTF-8");
      response
          .getWriter()
          .write(
              "{\"status\":429,\"detail\":\"Demasiados intentos fallidos. Vuelve a probar dentro de "
                  + seconds
                  + " s\"}");
      return;
    }
    chain.doFilter(request, response);
    int status = response.getStatus();
    if (status == HttpStatus.UNAUTHORIZED.value()) {
      throttle.failed(key);
    } else if (status < 400) {
      throttle.succeeded(key);
    }
  }

  private static String scope(HttpServletRequest request) {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    if (path.equals("/api/runner/register")) {
      return "runner";
    }
    String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (path.equals("/api/auth/login")
        || (authorization != null && authorization.regionMatches(true, 0, "Basic ", 0, 6))) {
      return "web";
    }
    return null;
  }
}
