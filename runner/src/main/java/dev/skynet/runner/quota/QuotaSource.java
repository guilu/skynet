package dev.skynet.runner.quota;

import dev.skynet.protocol.runner.QuotaSample;
import java.io.IOException;
import java.util.List;

/** Un CLI al que el runner pregunta su cuota sin lanzar ningún agente. */
public interface QuotaSource {

  /** El proveedor de las muestras ({@code codex}, {@code antigravity}). */
  String provider();

  /** La cuota ahora; vacía si el CLI no la da. Puede tardar unos segundos. */
  List<QuotaSample> read() throws IOException, InterruptedException;
}
