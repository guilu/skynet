import net from 'node:net'

/**
 * Proxy TCP entre el navegador y la web que permite cortar las conexiones abiertas, como haría
 * una red que se cae. Con `setOffline` del navegador no basta: no corta un stream ya abierto.
 */
export async function startCuttableProxy(port: number, target: { host: string; port: number }) {
  const sockets = new Set<net.Socket>()
  let down = false
  const server = net.createServer((client) => {
    if (down) {
      client.destroy()
      return
    }
    const upstream = net.connect(target)
    for (const s of [client, upstream]) {
      sockets.add(s)
      s.on('close', () => sockets.delete(s))
      s.on('error', () => s.destroy())
    }
    client.pipe(upstream).pipe(client)
  })
  await new Promise<void>((resolve) => server.listen(port, resolve))
  return {
    /** Corta todo lo abierto y rechaza conexiones nuevas hasta `restore()`. */
    cut() {
      down = true
      for (const s of sockets) s.destroy()
    },
    restore() {
      down = false
    },
    close: () => new Promise<void>((resolve) => server.close(() => resolve())),
  }
}
