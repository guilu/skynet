/** El hueco que ocupará el Skyny 3D mientras se descarga three: así no salta nada al llegar. */
export function SkynySlot({ size }: { size: number }) {
  return <span className="skyny-slot" style={{ width: size, height: size }} aria-hidden="true" />
}
