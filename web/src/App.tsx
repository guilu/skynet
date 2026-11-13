import { lazy, Suspense } from 'react'
import { Route, Routes } from 'react-router'
import { AppearanceLoader } from './appearance'
import { AppShell } from './components/AppShell'
import { AuthGate } from './components/AuthGate'
import { ActivityPage } from './pages/ActivityPage'
import { AgentRedirectPage } from './pages/AgentRedirectPage'
import { ApprovalPage } from './pages/ApprovalPage'
import { ApprovalsPage } from './pages/ApprovalsPage'
import { HomePage } from './pages/HomePage'
import { WorkPage } from './pages/WorkPage'
import { ProjectPage } from './pages/ProjectPage'
import { ProjectsPage } from './pages/ProjectsPage'
import { ImportProjectPage } from './pages/ImportProjectPage'
import { RunnersPage } from './pages/RunnersPage'
import { UsagePage } from './pages/UsagePage'
import { FactoryPage } from './pages/FactoryPage'
import { JudgesPage } from './pages/JudgesPage'
import { EvalsPage } from './pages/EvalsPage'
import { LessonsPage } from './pages/LessonsPage'
import { ResultsPage } from './pages/ResultsPage'
import { ProvidersPage } from './pages/ProvidersPage'
import { MaintenancePage } from './pages/MaintenancePage'
import { AdminPage } from './pages/AdminPage'
import { WorkflowsPage } from './pages/WorkflowsPage'
import { NewWorkflowPage } from './pages/NewWorkflowPage'
import { WorkflowPage } from './pages/WorkflowPage'
import { WorkItemPage } from './pages/WorkItemPage'
import { RunSkeleton } from './components/ui/Skeleton'
import { NotFound } from './pages/NotFound'
import { SettingsPage } from './pages/SettingsPage'

// La vista de una ejecución (paneles, cascada, conversación) se descarga al abrir la primera.
const RunPage = lazy(() => import('./pages/RunPage').then((m) => ({ default: m.RunPage })))

export default function App() {
  return (
    <>
      <AppearanceLoader />
      <AuthGate>
        <AppShell>
          <Routes>
            <Route path="/" element={<HomePage />} />
            <Route path="/work" element={<WorkPage />} />
            <Route path="/projects" element={<ProjectsPage />} />
            <Route path="/projects/import" element={<ImportProjectPage />} />
            <Route path="/projects/:projectId" element={<ProjectPage />} />
            <Route path="/work-items/:workItemId" element={<WorkItemPage />} />
            <Route path="/workflows" element={<WorkflowsPage />} />
            <Route path="/workflows/new" element={<NewWorkflowPage />} />
            <Route path="/workflows/:key" element={<WorkflowPage />} />
            <Route path="/runs" element={<FactoryPage />} />
            <Route path="/results" element={<ResultsPage />} />
            <Route path="/judges" element={<JudgesPage />} />
            <Route path="/lessons" element={<LessonsPage />} />
            <Route path="/evals" element={<EvalsPage />} />
            <Route
              path="/runs/:runId"
              element={
                <Suspense fallback={<RunSkeleton />}>
                  <RunPage />
                </Suspense>
              }
            />
            <Route path="/agent-runs/:agentId" element={<AgentRedirectPage />} />
            <Route path="/approvals" element={<ApprovalsPage />} />
            <Route path="/approvals/:approvalId" element={<ApprovalPage />} />
            <Route path="/admin" element={<AdminPage />} />
            <Route path="/providers" element={<ProvidersPage />} />
            <Route path="/runners" element={<RunnersPage />} />
            <Route path="/usage" element={<UsagePage />} />
            <Route path="/activity" element={<ActivityPage />} />
            <Route path="/settings" element={<SettingsPage />} />
            <Route path="/maintenance" element={<MaintenancePage />} />
            <Route path="*" element={<NotFound />} />
          </Routes>
        </AppShell>
      </AuthGate>
    </>
  )
}
