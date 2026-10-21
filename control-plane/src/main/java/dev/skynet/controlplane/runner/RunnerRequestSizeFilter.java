package dev.skynet.controlplane.runner;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Corta las peticiones JSON del runner que pasan de {@code skynet.runner.max-request}: con {@code
 * Content-Length}, antes de leerlas; sin él, al leer más de la cuenta. Las dos responden 413. Las
 * subidas de artefactos llevan su propio límite.
 */
final class RunnerRequestSizeFilter extends OncePerRequestFilter {

  /** Atributo que marca una petición cortada al leerla, para responder 413 y no 400. */
  static final String TOO_LARGE = RunnerRequestSizeFilter.class.getName() + ".tooLarge";

  private final long max;

  RunnerRequestSizeFilter(long max) {
    this.max = max;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    return !path.startsWith("/api/runner/") || path.startsWith("/api/runner/artifacts");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (request.getContentLengthLong() > max) {
      response.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());
      response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
      response
          .getWriter()
          .write(
              "{\"status\":413,\"detail\":\"La petición pasa de "
                  + max
                  + " bytes (skynet.runner.max-request)\"}");
      return;
    }
    chain.doFilter(new Limited(request, max), response);
  }

  /** Cuenta lo leído: una petición sin {@code Content-Length} tampoco pasa del límite. */
  private static final class Limited extends HttpServletRequestWrapper {

    private final long max;
    private ServletInputStream stream;

    Limited(HttpServletRequest request, long max) {
      super(request);
      this.max = max;
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
      if (stream == null) {
        ServletInputStream in = super.getInputStream();
        stream =
            new ServletInputStream() {
              private long read;

              @Override
              public int read() throws IOException {
                int b = in.read();
                if (b >= 0) {
                  count(1);
                }
                return b;
              }

              @Override
              public int read(byte[] buffer, int off, int len) throws IOException {
                int n = in.read(buffer, off, len);
                if (n > 0) {
                  count(n);
                }
                return n;
              }

              private void count(long n) throws IOException {
                read += n;
                if (read > max) {
                  setAttribute(TOO_LARGE, max);
                  throw new IOException("La petición pasa de " + max + " bytes");
                }
              }

              @Override
              public boolean isFinished() {
                return in.isFinished();
              }

              @Override
              public boolean isReady() {
                return in.isReady();
              }

              @Override
              public void setReadListener(ReadListener listener) {
                in.setReadListener(listener);
              }
            };
      }
      return stream;
    }
  }
}
