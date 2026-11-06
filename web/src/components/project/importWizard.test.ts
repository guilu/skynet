import { describe, expect, it } from 'vitest'
import type { CatalogEntry, DiscoveredRepository } from '../../api'
import {
  branchSource,
  displayName,
  importRequest,
  initialForm,
  matchesSearch,
  remoteSource,
  shortPath,
  shortRemote,
  validationSource,
} from './importWizard'

const repo = (over: Partial<DiscoveredRepository> = {}): DiscoveredRepository => ({
  path: '/home/diego/code/forma',
  name: 'forma',
  projectName: 'forma-app',
  projectNameSource: 'settings.gradle.kts',
  description: null,
  descriptionSource: null,
  remoteUrl: 'git@github.com:guilu/forma.git',
  defaultBranch: 'main',
  defaultBranchSource: 'origin/HEAD',
  tool: 'gradle',
  validationCommand: './gradlew check',
  testReportPaths: ['**/build/test-results/**/*.xml'],
  protectedPaths: ['.github/workflows/**'],
  lastCommit: null,
  lastCommitSubject: null,
  lastCommitAt: null,
  dirty: false,
  agentInstructions: false,
  ...over,
})

const entry = (over: Partial<CatalogEntry> = {}): CatalogEntry => ({
  repository: repo(),
  registered: null,
  github: null,
  ...over,
})

describe('asistente de importación', () => {
  it('acorta rutas y remotos', () => {
    expect(shortPath('/home/diego/code/forma')).toBe('~/code/forma')
    expect(shortPath('/Users/diego/code')).toBe('~/code')
    expect(shortPath('/srv/repos/x')).toBe('/srv/repos/x')
    expect(shortRemote('git@github.com:guilu/forma.git')).toBe('github.com/guilu/forma')
    expect(shortRemote('https://github.com/guilu/forma.git')).toBe('github.com/guilu/forma')
    expect(shortRemote('ssh://git@host:22/g/r.git')).toBe('host:22/g/r')
  })

  it('propone el nombre y la descripción, con la de GitHub de reserva', () => {
    expect(displayName('forma-app')).toBe('Forma app')
    const form = initialForm(
      entry({
        github: { fullName: 'guilu/forma', covered: true, description: 'Forms', htmlUrl: '' },
      }),
      'FOR',
    )
    expect(form).toMatchObject({
      key: 'FOR',
      name: 'Forma app',
      description: 'Forms',
      repoName: 'forma',
      testReportPaths: '**/build/test-results/**/*.xml',
    })
    expect(
      initialForm(entry({ repository: repo({ description: 'Del README' }) })).description,
    ).toBe('Del README')
  })

  it('arma la petición sin campos vacíos', () => {
    const form = { ...initialForm(entry(), 'FORMA'), remoteUrl: ' ', protectedPaths: '\n a/** \n' }
    const req = importRequest('/home/diego/code/forma', form)
    expect(req.repository.remoteUrl).toBeUndefined()
    expect(req.repository.protectedPaths).toEqual(['a/**'])
    expect(req.repository.localPath).toBe('/home/diego/code/forma')
    expect(req.description).toBeUndefined()
  })

  it('explica de dónde sale cada dato', () => {
    expect(branchSource(repo({ defaultBranchSource: 'current' }))).toContain('rama actual')
    expect(validationSource(repo({ tool: null, validationCommand: null }))).toContain(
      'No se reconoce',
    )
    expect(validationSource(repo())).toBe('Sugerido para Gradle')
    expect(remoteSource(entry({ repository: repo({ remoteUrl: null }) })).text).toContain(
      'Sin remoto',
    )
    expect(
      remoteSource(
        entry({ github: { fullName: 'g/f', covered: true, description: null, htmlUrl: '' } }),
      ).ok,
    ).toBe(true)
  })

  it('busca por nombre, ruta o remoto', () => {
    expect(matchesSearch(entry(), 'FORMA')).toBe(true)
    expect(matchesSearch(entry(), 'guilu/forma')).toBe(true)
    expect(matchesSearch(entry(), 'akademia')).toBe(false)
  })
})
