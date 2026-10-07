package dev.skynet.controlplane.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Lee el token CSRF en cada petición para que la cookie {@code XSRF-TOKEN} llegue a la web desde la
 * primera respuesta; sin esto, el token es diferido y la cookie no se escribe hasta que alguien lo
 * pide.
 */
final class CsrfCookieFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (request.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken token) {
      token.getToken();
    }
    chain.doFilter(request, response);
  }
}
