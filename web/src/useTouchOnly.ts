import { useMediaQuery } from './useMediaQuery'

/**
 * Si el único puntero es un dedo (un móvil, una tableta sin ratón ni trackpad). Monaco no admite
 * bien estos dispositivos: en Safari de iOS no deja seleccionar, copiar ni pegar. Ahí se usa el
 * texto nativo del navegador.
 */
export function useTouchOnly(): boolean {
  const coarse = useMediaQuery('(pointer: coarse)')
  const fine = useMediaQuery('(any-pointer: fine)')
  return coarse && !fine
}
