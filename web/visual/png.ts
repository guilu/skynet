/** Ancho y alto de un PNG, leídos de su cabecera IHDR. */
export function pngSize(data: Uint8Array): { width: number; height: number } {
  const signature = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]
  if (data.length < 24 || signature.some((b, i) => data[i] !== b)) throw new Error('no es un PNG')
  const view = new DataView(data.buffer, data.byteOffset, data.byteLength)
  return { width: view.getUint32(16), height: view.getUint32(20) }
}
