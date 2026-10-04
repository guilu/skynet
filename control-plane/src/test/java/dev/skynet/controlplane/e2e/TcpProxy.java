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
  private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
  private volatile ServerSocket server;
  private int port;

  TcpProxy(int targetPort) throws IOException {
    this.targetPort = targetPort;
    listen(0);
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
    listen(port);
  }

  private void listen(int requestedPort) throws IOException {
    ServerSocket socket = new ServerSocket();
    socket.setReuseAddress(true);
    socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), requestedPort));
    server = socket;
    port = socket.getLocalPort();
    Thread.ofVirtual().start(() -> accept(socket));
  }

  private void accept(ServerSocket listener) {
    while (!listener.isClosed()) {
      try {
        Socket client = listener.accept();
        Socket upstream = new Socket(InetAddress.getLoopbackAddress(), targetPort);
        sockets.add(client);
        sockets.add(upstream);
        Thread.ofVirtual().start(() -> pump(client, upstream));
        Thread.ofVirtual().start(() -> pump(upstream, client));
      } catch (IOException e) {
        // Cerrado por cut() o close().
      }
    }
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
