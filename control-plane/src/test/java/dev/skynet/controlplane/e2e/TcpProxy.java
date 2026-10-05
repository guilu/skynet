package dev.skynet.controlplane.e2e;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Proxy TCP entre el runner y el control plane para simular cortes de red: {@link #cut()} cierra
 * las conexiones abiertas y deja de aceptar nuevas; {@link #restore()} vuelve a escuchar en el
 * mismo puerto.
 */
final class TcpProxy implements AutoCloseable {

  private final int targetPort;
  private final int port;
  private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
  private ServerSocket server;

  TcpProxy(int targetPort) throws IOException {
    this.targetPort = targetPort;
    this.server = listen(0);
    this.port = server.getLocalPort();
  }

  int port() {
    return port;
  }

  synchronized void cut() throws IOException {
    server.close();
    for (Socket socket : sockets) {
      socket.close();
    }
    sockets.clear();
  }

  synchronized void restore() throws IOException {
    server = listen(port);
  }

  private ServerSocket listen(int requestedPort) throws IOException {
    ServerSocket socket = new ServerSocket();
    try {
      socket.setReuseAddress(true);
      socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), requestedPort));
    } catch (IOException e) {
      socket.close();
      throw e;
    }
    Thread.ofVirtual().start(() -> accept(socket));
    return socket;
  }

  private void accept(ServerSocket listener) {
    while (!listener.isClosed()) {
      try {
        connect(listener.accept());
      } catch (IOException e) {
        // Cerrado por cut() o close().
      }
    }
  }

  private void connect(Socket client) throws IOException {
    sockets.add(client);
    Socket upstream;
    try {
      upstream = new Socket(InetAddress.getLoopbackAddress(), targetPort);
    } catch (IOException e) {
      closeQuietly(client);
      throw e;
    }
    sockets.add(upstream);
    Thread.ofVirtual().start(() -> pump(client, upstream));
    Thread.ofVirtual().start(() -> pump(upstream, client));
  }

  private void pump(Socket from, Socket to) {
    try (InputStream in = from.getInputStream();
        OutputStream out = to.getOutputStream()) {
      in.transferTo(out);
    } catch (IOException e) {
      // Conexión cortada.
    } finally {
      closeQuietly(from);
      closeQuietly(to);
    }
  }

  private void closeQuietly(Socket socket) {
    sockets.remove(socket);
    try {
      socket.close();
    } catch (IOException e) {
      // Ya cerrado.
    }
  }

  @Override
  public void close() throws IOException {
    cut();
  }
}
