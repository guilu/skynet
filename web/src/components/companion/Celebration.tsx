import type { CSSProperties } from 'react'

const COLORS = ['var(--primary)', 'var(--ok)', 'var(--warn)', 'var(--bad)', 'var(--skyny-orange)']

/** Los trozos de confeti: sitio, retraso, giro y color fijos, para que no cambien al pintar. */
const PIECES = Array.from({ length: 32 }, (_, i) => ({
  x: (i * 37) % 100,
  delay: ((i * 53) % 40) * 10,
  turn: ((i * 71) % 360) - 180,
  drift: ((i * 29) % 60) - 30,
  color: COLORS[i % COLORS.length],
  round: i % 3 === 0,
}))

/** Confeti a pantalla completa (SY-C). Decorativo; con «reducir movimiento» no sale. */
export function Celebration() {
  return (
    <div className="celebration" aria-hidden="true">
      {PIECES.map((p, i) => (
        <span
          key={i}
          className="celebration-piece"
          data-round={p.round || undefined}
          style={
            {
              left: `${p.x}%`,
              background: p.color,
              animationDelay: `${p.delay}ms`,
              '--turn': `${p.turn}deg`,
              '--drift': `${p.drift}px`,
            } as CSSProperties
          }
        />
      ))}
    </div>
  )
}
