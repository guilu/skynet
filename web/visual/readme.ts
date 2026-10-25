import { capturedScreens, fileName, variants, type Inventory } from './inventory.ts'

const RAW = 'https://raw.githubusercontent.com/guilu/skynet/main/docs/screenshots'

/** docs/screenshots/README.md: el índice de la galería, generado a partir del inventario. */
export function galleryReadme(inv: Inventory): string {
  const { viewport, theme } = inv.canonical
  const combos = variants(inv)
  const lines: string[] = [
    '# Galería visual de Skynet',
    '',
    '<!-- Generado por web/visual/finish.ts a partir de web/visual/navigation-map.json: no editar a mano. -->',
    '',
    'Capturas de todas las pantallas de Skynet con datos de demostración (proyecto `DEMO`, repositorio',
    '`analizador-texto`), en escritorio y móvil, tema claro y oscuro. Sirven de referencia para',
    'rediseñar, comparar antes y después y preparar publicaciones.',
    '',
    '| Combinación | Tamaño | Carpeta | En Git |',
    '| --- | --- | --- | --- |',
    ...combos.map((c) => {
      const size = inv.viewports[c.viewport]
      const canonical = c.viewport === viewport && c.theme === theme
      return `| ${c.viewport} · ${c.theme} | ${size.width} × ${size.height} | \`${c.viewport}/${c.theme}/\` | ${canonical ? 'sí (canónica)' : 'no: ZIP y artifact'} |`
    }),
    '',
    `En Git solo se guarda la combinación canónica (\`${viewport}/${theme}\`); las demás y`,
    '`skynet-screenshots.zip` salen al regenerar en local o en la Action **Galería visual**.',
    '',
  ]
  for (const group of inv.groups) {
    const screens = capturedScreens(inv).filter((s) => s.group === group.id)
    if (!screens.length) continue
    lines.push(
      `## ${group.title}`,
      '',
      '| # | Pantalla | Ruta o estado | Vista | Variantes |',
      '| --- | --- | --- | --- | --- |',
    )
    for (const s of screens) {
      const file = fileName(s)
      const thumb = `<a href="${viewport}/${theme}/${file}"><img src="${viewport}/${theme}/${file}" width="240" alt="${s.title}"></a>`
      const others = combos
        .filter((c) => !(c.viewport === viewport && c.theme === theme))
        .map((c) => `[${c.viewport} ${c.theme}](${c.viewport}/${c.theme}/${file})`)
        .join(' · ')
      const notes = s.notes ? `<br><sub>${s.notes}</sub>` : ''
      lines.push(`| ${s.order} | **${s.title}**${notes} | \`${s.route}\` | ${thumb} | ${others} |`)
    }
    lines.push('')
  }
  const exceptions = inv.screens.filter((s) => s.exception)
  lines.push('## Excepciones', '')
  if (exceptions.length) {
    for (const s of exceptions) lines.push(`- **${s.title}** (\`${s.id}\`): ${s.exception}`)
  } else {
    lines.push('Ninguna: todas las pantallas del inventario tienen captura.')
  }
  lines.push(
    '',
    'Notas sobre vistas pedidas que no existen como tales:',
    '',
    '- **Proyecto: resumen**: el proyecto no tiene esa pestaña; la cabecera y la pestaña Trabajos hacen de resumen (`project-work`).',
    '- **Ejecución: conversación**: es la vista por defecto del panel central y sale en `run-completed`.',
    '- **Dashboard con actividad** y **con «Necesita tu atención»**: los dos estados caben en la misma captura (`dashboard`).',
    '',
    '## Regenerar',
    '',
    '```bash',
    'cd web',
    'npm run screenshots',
    '```',
    '',
    'Necesita Java 21, Node 22 y Docker (o PostgreSQL con `initdb` en el PATH). Siembra los datos en',
    'una base desechable, nunca en la normal, y lo borra todo al terminar. Detalles en',
    '[`docs/tests/probar-vs-a.md`](../tests/probar-vs-a.md).',
    '',
    'Sin máquina preparada: Actions → **Galería visual** → *Run workflow*, y descarga el artifact',
    '`skynet-screenshots` (no hace commit ni push).',
    '',
    'Las fechas relativas («hace 3 min») y las duraciones salen iguales en cada ejecución porque el',
    'reloj del navegador se para justo después de sembrar; las fechas absolutas y los ids son los del',
    'día en que se generan.',
    '',
    '**Cuándo actualizar las capturas de Git**: al cambiar el diseño de una pantalla, regenerar y',
    `commitear solo \`${viewport}/${theme}/\` y este README.`,
    '',
    '## Descargar una imagen suelta',
    '',
    'Cada imagen versionada tiene un enlace directo, por ejemplo:',
    '',
    '```text',
    `${RAW}/${viewport}/${theme}/${fileName(capturedScreens(inv)[1] ?? capturedScreens(inv)[0])}`,
    '```',
    '',
    'En GitHub, abrir la imagen y pulsar **Download raw file**.',
    '',
  )
  return lines.join('\n')
}
