/** Letreros de la casa: van en orden aleatorio y se repiten mientras sigas en el login. */
export const HOUSE_LINES = [
  'TUS AGENTES TE ESPERAN',
  'TODO EN ORDEN... CREO',
  'CERO MERGES ROTOS HOY',
  'LOS TESTS PASAN (CASI)',
  'LISTO PARA DESPLEGAR',
  '01001000 01001001',
]

/**
 * Al escribir la contraseña (antes de taparse los ojos) y si falla la entrada: a trozos, para que
 * se lean grandes en la pantalla.
 */
export const PASSWORD_LINE = ['NO MIRO,', 'PROMETIDO']
export const FAILED_LINE = ['UPS,', 'OTRA VEZ']
/** Cuánto tarda en taparse los ojos al ir a la contraseña: lo que tarda en decir que no mira. */
export const COVER_AFTER_MS = 2900

/** Saludo según la hora (local). */
export function greeting(date: Date) {
  const hour = date.getHours()
  if (hour >= 6 && hour < 13) return 'BUENOS DÍAS'
  if (hour >= 13 && hour < 21) return 'BUENAS TARDES'
  return 'BUENAS NOCHES'
}

/** Los que solo tocan a ciertas horas: de madrugada, por la mañana y el viernes por la tarde. */
export function timelyLines(date: Date): string[] {
  const hour = date.getHours()
  if (hour < 6) return ['¿OTRA NOCHE DE DEPLOYS?', '404 SUEÑO NO ENCONTRADO']
  if (hour >= 7 && hour < 11) return ['¿CAFÉ?']
  if (date.getDay() === 5 && hour >= 15) return ['NADA DE DEPLOYS EN VIERNES']
  return []
}

/**
 * Lo que pasa por la pantalla de Skyny en el login, después del «HOLA»: el saludo de la hora, los
 * de la hora que toquen y los de la casa, barajados. `loopFrom` es dónde vuelve a empezar la
 * lista al acabar (en los de la casa: el saludo sale una vez).
 */
export function loginTicker(date: Date, random: () => number = Math.random) {
  const house = [...HOUSE_LINES]
  for (let i = house.length - 1; i > 0; i--) {
    const j = Math.floor(random() * (i + 1))
    ;[house[i], house[j]] = [house[j], house[i]]
  }
  const intro = [greeting(date), ...timelyLines(date)]
  return { lines: [...intro, ...house], loopFrom: intro.length }
}
