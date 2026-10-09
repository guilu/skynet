import { useQuery } from '@tanstack/react-query'
import { useEffect } from 'react'
import { api } from './api'
import { faviconSvg } from './lib/brand'
import { cleanPalette, derivePalette, paletteCss, type Palette } from './lib/palette'

export const APPEARANCE_KEY = ['appearance']

const STORAGE_KEY = 'skynet.palette'
const STYLE_ID = 'skynet-palette'
const FAVICON_HREF = '/favicon.svg'

/**
 * Aplica la paleta a toda la web: una hoja con sus tokens encima de `index.css` y
 * `data-palette` en `<html>` (Monaco lo vigila para volver a leer los colores). La guarda también
 * en este navegador, para pintar la siguiente carga con ella antes de que responda el servidor.
 * El favicon sigue al acento.
 */
export function applyPalette(palette: Palette) {
  const clean = cleanPalette(palette)
  const css = paletteCss(clean)
  let style = document.getElementById(STYLE_ID)
  if (css && !style) {
    style = document.createElement('style')
    style.id = STYLE_ID
    document.head.appendChild(style)
  }
  if (style) style.textContent = css
  const root = document.documentElement
  if (css) root.setAttribute('data-palette', Object.values(clean).join(''))
  else root.removeAttribute('data-palette')
  applyFavicon(clean)
  try {
    if (css) localStorage.setItem(STORAGE_KEY, JSON.stringify(clean))
    else localStorage.removeItem(STORAGE_KEY)
  } catch {
    // Sin almacenamiento (modo privado): la paleta llega del servidor en cada carga.
  }
}

/** Favicon con el acento de la paleta; sin acento propio, el estático de `public/`. */
function applyFavicon(palette: Palette) {
  const icon = document.querySelector<HTMLLinkElement>('link[rel="icon"]')
  if (!icon) return
  if (!palette.primary) {
    icon.href = FAVICON_HREF
    return
  }
  const { light } = derivePalette({ primary: palette.primary })
  icon.href = `data:image/svg+xml,${encodeURIComponent(faviconSvg(light.primary, light['on-primary']))}`
}

/** La última paleta vista en este navegador, al arrancar. */
export function applyCachedPalette() {
  try {
    const cached = localStorage.getItem(STORAGE_KEY)
    if (cached) applyPalette(JSON.parse(cached) as Palette)
  } catch {
    // Valor corrupto o sin almacenamiento: se queda la de Skynet hasta que responda el servidor.
  }
}

/** Pide la paleta guardada y la aplica; si no llega, se queda la que hubiera. */
export function useAppearance() {
  const appearance = useQuery({
    queryKey: APPEARANCE_KEY,
    queryFn: api.appearance,
    retry: false,
    staleTime: Infinity,
  })
  useEffect(() => {
    if (appearance.data) applyPalette(appearance.data.colors)
  }, [appearance.data])
  return appearance
}

/** Carga la paleta en cuanto arranca la web, también en el login. */
export function AppearanceLoader() {
  useAppearance()
  return null
}
