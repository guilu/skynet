import { NavLink, Route, Routes } from 'react-router'
import { HealthIndicator } from './HealthIndicator'
import { ActivityPage } from './pages/ActivityPage'
import { ProjectPage } from './pages/ProjectPage'
import { ProjectsPage } from './pages/ProjectsPage'
import { RunPage } from './pages/RunPage'
import { WorkItemPage } from './pages/WorkItemPage'

export default function App() {
  return (
    <div className="app">
      <header className="topbar">
        <span className="brand">Skynet</span>
        <nav>
          <NavLink to="/" end>
            Proyectos
          </NavLink>
          <NavLink to="/activity">Actividad</NavLink>
        </nav>
        <HealthIndicator />
      </header>
      <main className="content">
        <Routes>
          <Route path="/" element={<ProjectsPage />} />
          <Route path="/projects/:projectId" element={<ProjectPage />} />
          <Route path="/work-items/:workItemId" element={<WorkItemPage />} />
          <Route path="/runs/:runId" element={<RunPage />} />
          <Route path="/activity" element={<ActivityPage />} />
          <Route path="*" element={<p>Página no encontrada.</p>} />
        </Routes>
      </main>
    </div>
  )
}
