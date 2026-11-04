-- FS-B: el repositorio en el que se trabaja un trabajo. Opcional: los trabajos antiguos sin
-- ejecuciones ni disparador se quedan sin él. Al purgar el repositorio, el trabajo lo pierde.
ALTER TABLE work_item
    ADD COLUMN repository_id uuid REFERENCES repository (id) ON DELETE SET NULL;

-- Relleno: el repositorio de su última ejecución…
UPDATE work_item w
SET repository_id = (SELECT r.repository_id
                     FROM workflow_run r
                     WHERE r.work_item_id = w.id AND r.repository_id IS NOT NULL
                     ORDER BY r.created_at DESC
                     LIMIT 1);

-- …o, si no tiene, el del disparador que lo creó.
UPDATE work_item w
SET repository_id = (SELECT l.repository_id
                     FROM trigger_launch l
                     WHERE l.work_item_id = w.id AND l.repository_id IS NOT NULL
                     ORDER BY l.created_at DESC
                     LIMIT 1)
WHERE w.repository_id IS NULL;

UPDATE work_item w
SET repository_id = t.repository_id
FROM trigger_rule t
WHERE w.repository_id IS NULL
  AND t.work_item_id = w.id;

CREATE INDEX work_item_repository_idx ON work_item (repository_id);
