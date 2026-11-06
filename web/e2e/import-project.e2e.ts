import { expect, test } from '@playwright/test'
import { execFileSync } from 'node:child_process'
import { mkdirSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { expectAccessible, login } from './helpers.ts'

/**
 * Importar proyecto (IP-C) contra el runner de verdad: la prueba clona (crea) un repositorio en la
 * carpeta raíz que cataloga el runner (SKYNET_REPO_ROOTS = $E2E_REPO_ROOT), pulsa «Volver a
 * buscar», lo elige, revisa lo que se dedujo del package.json y crea proyecto y repositorio de una
 * vez. El mismo repositorio sale después como «Ya está en …».
 */

test.use({ baseURL: 'http://localhost:4173' })

const root = process.env.E2E_REPO_ROOT ?? `${process.env.E2E_OUT ?? '/tmp'}/code`

function cloneFake(name: string): string {
  const dir = join(root, name)
  mkdirSync(dir, { recursive: true })
  const git = (...args: string[]) => execFileSync('git', ['-C', dir, ...args], { stdio: 'pipe' })
  git('init', '-q', '-b', 'main')
  writeFileSync(
    join(dir, 'package.json'),
    JSON.stringify({
      name,
      description: 'Repositorio de prueba para importar',
      scripts: { test: 'vitest run' },
    }),
  )
  writeFileSync(join(dir, 'package-lock.json'), '{}\n')
  writeFileSync(join(dir, 'README.md'), `# ${name}\n\nOtra descripción que no se usa.\n`)
  git('add', '.')
  git('-c', 'user.email=e2e@skynet', '-c', 'user.name=e2e', 'commit', '-qm', 'Inicial')
  git('remote', 'add', 'origin', `https://e2e-token@github.com/guilu/${name}.git`)
  return dir
}

test('Importar proyecto: del repositorio clonado al proyecto en cuatro pasos', async ({ page }) => {
  test.setTimeout(180_000)
  const name = `imp${Date.now().toString(36)}`
  cloneFake(name)

  await page.goto('/')
  await login(page)
  await expect(page.getByRole('navigation', { name: 'Navegación principal' })).toBeVisible()
  await page.goto('/projects')
  await page.getByRole('link', { name: 'Importar proyecto' }).click()
  await expect(page.getByRole('heading', { name: 'Importar proyecto' })).toBeVisible()

  await test.step('«Volver a buscar» trae el repositorio nuevo', async () => {
    await page.getByRole('button', { name: 'Volver a buscar' }).click()
    // El runner reescanea en el siguiente latido; la lista se refresca sola mientras tanto.
    await expect(page.getByRole('radio', { name: new RegExp(name) })).toBeVisible({
      timeout: 60_000,
    })
    await page.getByLabel('Buscar repositorios').fill(name)
    const candidate = page.getByRole('radio', { name: new RegExp(name) })
    await expect(candidate).toBeEnabled()
    await candidate.check()
    await expectAccessible(page, 'importar proyecto, paso 1')
    await page.getByRole('button', { name: 'Siguiente' }).click()
  })

  await test.step('Proyecto: nombre y descripción del package.json', async () => {
    const display = name.charAt(0).toUpperCase() + name.slice(1)
    await expect(page.getByRole('textbox', { name: /^Nombre(?! del)/ })).toHaveValue(display)
    await expect(page.getByLabel('Descripción')).toHaveValue('Repositorio de prueba para importar')
    await expect(page.getByLabel('Clave')).not.toHaveValue('')
    await page.getByRole('button', { name: 'Siguiente' }).click()
  })

  await test.step('Configuración: remoto sin credenciales y validación de npm', async () => {
    await expect(page.getByLabel('Remoto')).toHaveValue(`https://github.com/guilu/${name}.git`)
    await expect(page.getByLabel('Comando de validación')).toHaveValue('npm test')
    await expect(page.getByLabel('Rama por defecto')).toHaveValue('main')
    await expectAccessible(page, 'importar proyecto, paso 3')
    await page.getByRole('button', { name: 'Siguiente' }).click()
  })

  await test.step('Resumen y «Crear proyecto»', async () => {
    await expect(page.getByLabel('Lanzar «Conocer el repositorio» al terminar')).not.toBeChecked()
    await page.getByRole('button', { name: 'Crear proyecto' }).click()
    await expect(page).toHaveURL(/\/projects\/[0-9a-f-]+$/)
    await expect(
      page.getByRole('status').filter({ hasText: 'Proyecto importado desde' }),
    ).toBeVisible()
    await expect(page.getByRole('heading', { level: 1 })).toContainText(
      name.charAt(0).toUpperCase() + name.slice(1),
    )
  })

  await test.step('el repositorio ya importado sale como «Ya está en …»', async () => {
    await page.goto('/projects/import')
    await page.getByLabel('Buscar repositorios').fill(name)
    await expect(page.getByRole('radio', { name: new RegExp(name) })).toBeDisabled()
    await expect(page.getByText('Ya está en')).toBeVisible()
  })
})
