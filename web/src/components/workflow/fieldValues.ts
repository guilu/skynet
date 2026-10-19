/** Un valor leído del YAML como texto para un campo: lo que no es texto, vacío. */
export const str = (value: unknown) => (typeof value === 'string' ? value : '')
