package dev.skynet.runner.supervisor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Lee la salida de error de un proceso y conserva solo los últimos bytes. */
final class StderrTail {

  static final int MAX = 8 * 1024;

  private final Thread thread;
  private final byte[] buffer = new byte[MAX];
  private int length;

  StderrTail(InputStream in, String name) {
    thread = Thread.ofPlatform().daemon().name(name).start(() -> drain(in));
  }

  private void drain(InputStream in) {
    byte[] chunk = new byte[4096];
    try (in) {
      int n;
      while ((n = in.read(chunk)) != -1) {
        append(chunk, n);
      }
    } catch (IOException e) {
      // El proceso ha terminado.
    }
  }

  private synchronized void append(byte[] chunk, int n) {
    if (n >= MAX) {
      System.arraycopy(chunk, n - MAX, buffer, 0, MAX);
      length = MAX;
      return;
    }
    int overflow = length + n - MAX;
    if (overflow > 0) {
      System.arraycopy(buffer, overflow, buffer, 0, length - overflow);
      length -= overflow;
    }
    System.arraycopy(chunk, 0, buffer, length, n);
    length += n;
  }

  void join() throws InterruptedException {
    thread.join();
  }

  synchronized String tail() {
    return new String(buffer, 0, length, StandardCharsets.UTF_8);
  }
}
