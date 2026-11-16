package dev.skynet.controlplane.security;

import dev.skynet.controlplane.chat.ChatTokens;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Las peticiones con un token de conversación ({@code Authorization: Bearer skc_…}, CH-B): el MCP
 * de Skynet leyendo la API en nombre de Skyny. Solo pasan los {@code GET} de {@link
 * ChatTokens#readable}; el resto da 403 sin llegar a la API. Con un token que no vale, 401. No crea
 * sesión.
 *
 * <p>La API del runner ({@code /api/runner/**}) comprueba sus propios tokens, también los de
 * conversación, y no pasa por aquí.
 */
final class ChatTokenFilter extends OncePerRequestFilter {

  /** Quién hace la petición: Skyny, en nombre de la conversación. */
  static final String PRINCIPAL_PREFIX = "skyny:";

  private final ChatTokens tokens;
  private final SecurityContextHolderStrategy contexts =
      SecurityContextHolder.getContextHolderStrategy();

  ChatTokenFilter(ChatTokens tokens) {
    this.tokens = tokens;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !ChatTokens.isChatToken(request.getHeader(HttpHeaders.AUTHORIZATION))
        || path(request).startsWith("/api/runner/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (!ChatTokens.readable(request.getMethod(), path(request))) {
      response.sendError(HttpServletResponse.SC_FORBIDDEN);
      return;
    }
    Optional<UUID> conversation = tokens.authenticate(request.getHeader(HttpHeaders.AUTHORIZATION));
    if (conversation.isEmpty()) {
      response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
      return;
    }
    SecurityContext context = contexts.createEmptyContext();
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(
            PRINCIPAL_PREFIX + conversation.get(),
            null,
            List.of(new SimpleGrantedAuthority("ROLE_CHAT"))));
    contexts.setContext(context);
    try {
      chain.doFilter(request, response);
    } finally {
      contexts.clearContext();
    }
  }

  private static String path(HttpServletRequest request) {
    return request.getRequestURI().substring(request.getContextPath().length());
  }
}
