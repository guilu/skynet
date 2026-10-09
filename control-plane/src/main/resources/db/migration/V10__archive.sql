-- Archivar (AE-A): lo archivado sale de las listas y del dashboard, y se puede restaurar. Archivar
-- un proyecto o un trabajo no toca a sus hijos: las consultas ocultan lo que cuelga de algo
-- archivado, así que restaurar el padre lo devuelve todo como estaba.
ALTER TABLE project ADD COLUMN archived_at timestamptz;
ALTER TABLE repository ADD COLUMN archived_at timestamptz;
ALTER TABLE work_item ADD COLUMN archived_at timestamptz;
ALTER TABLE workflow_run ADD COLUMN archived_at timestamptz;
ALTER TABLE runner ADD COLUMN archived_at timestamptz;

-- Las listas piden casi siempre lo no archivado.
CREATE INDEX workflow_run_active_list_idx ON workflow_run (created_at DESC)
    WHERE archived_at IS NULL;
