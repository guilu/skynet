-- Reintentos automáticos (W6-A).

-- Por qué falló una invocación: decide si su fase se reintenta (`retry.on` en el YAML).
ALTER TABLE agent_run
    ADD COLUMN failure       text
        CHECK (failure IN ('LOST', 'START', 'ERROR', 'TIMEOUT', 'BUDGET', 'VERIFICATION')),
    -- Instantánea del worktree que continúa, tal como estaba al empezar: el reintento vuelve a
    -- ella en vez de partir de lo que dejó el intento fallido.
    ADD COLUMN base_snapshot text;

-- Cada reintento es un intento nuevo de la fase (stage_run.attempt + 1) que no empieza antes de
-- esta hora: la espera de `retry.delay`.
ALTER TABLE stage_run ADD COLUMN not_before timestamptz;
