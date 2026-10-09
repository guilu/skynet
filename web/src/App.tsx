import { lazy, Suspense } from 'react'
import { Route, Routes } from 'react-router'
import { AppShell } from './components/AppShell'
import { AuthGate } from './components/AuthGate'
import { ActivityPage } from './pages/ActivityPage'
import { AgentRedirectPage } from './pages/AgentRedirectPage'
import { DashboardPage } from './pages/DashboardPage'
import { ProjectPage } from './pages/ProjectPage'
import { ProjectsPage } from './pages/ProjectsPage'
import { RunnersPage } from './pages/RunnersPage'
import { RunsPage } from './pages/RunsPage'
import { WorkflowsPage } from './pages/WorkflowsPage'
import { WorkItemPage } from './pages/WorkItemPage'

// La vista de una ejecución (paneles, cascada, conversación) se descarga al abrir la primera.
const RunPage = lazy(() => import('./pages/RunPage').then((m) => ({ default: m.RunPage })))

export default function App() {
  return (
    <AuthGate>
      <AppShell>
        <Routes>
          <Route path="/" element={<DashboardPage />} />
          <Route path="/projects" element={<ProjectsPage />} />
          <Route path="/projects/:projectId" element={<ProjectPage />} />
          <Route path="/work-items/:workItemId" element={<WorkItemPage />} />
          <Route path="/workflows" element={<WorkflowsPage />} />
          <Route path="/runs" element={<RunsPage />} />
          <Route
            path="/runs/:runId"
            element={
              <Suspense fallback={<p className="muted">Cargando la ejecución…</p>}>
                <RunPage />
              </Suspense>
            }
          />
          <Route path="/agent-runs/:agentId" element={<AgentRedirectPage />} />
          <Route path="/runners" element={<RunnersPage />} />
          <Route path="/activity" element={<ActivityPage />} />
          <Route path="*" element={<p>Página no encontrada.</p>} />
        </Routes>
      </AppShell>
    </AuthGate>
  )
}
