-- Datos que necesitan las proyecciones de lectura del control plane (ADR-0001, M3).

ALTER TABLE agent_run
    -- Tokens leídos de la caché del prompt y escritos en ella. Sin ellos la entrada parece mucho
    -- menor de lo que es: con caché, input_tokens suele quedarse en unas pocas unidades.
    ADD COLUMN cache_read_tokens     bigint,
    ADD COLUMN cache_creation_tokens bigint,
    -- Actividad actual: último tipo de evento recibido y herramienta en curso, si la hay.
    ADD COLUMN last_event_type       text,
    ADD COLUMN current_tool          text;

-- Listado de ejecuciones por estado (página Ejecuciones y dashboard).
CREATE INDEX workflow_run_status_idx ON workflow_run (status, created_at DESC);
-- Detección de agentes sin actividad.
CREATE INDEX agent_run_active_idx ON agent_run (status, last_activity_at)
    WHERE status IN ('THINKING', 'EXECUTING');
