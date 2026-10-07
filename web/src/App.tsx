import { Route, Routes } from 'react-router'
import { AppShell } from './components/AppShell'
import { AuthGate } from './components/AuthGate'
import { ActivityPage } from './pages/ActivityPage'
import { AgentRedirectPage } from './pages/AgentRedirectPage'
import { DashboardPage } from './pages/DashboardPage'
import { ProjectPage } from './pages/ProjectPage'
import { ProjectsPage } from './pages/ProjectsPage'
import { RunnersPage } from './pages/RunnersPage'
import { RunPage } from './pages/RunPage'
import { RunsPage } from './pages/RunsPage'
import { WorkflowsPage } from './pages/WorkflowsPage'
import { WorkItemPage } from './pages/WorkItemPage'

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
          <Route path="/runs/:runId" element={<RunPage />} />
          <Route path="/agent-runs/:agentId" element={<AgentRedirectPage />} />
          <Route path="/runners" element={<RunnersPage />} />
          <Route path="/activity" element={<ActivityPage />} />
          <Route path="*" element={<p>Página no encontrada.</p>} />
        </Routes>
      </AppShell>
    </AuthGate>
  )
}
