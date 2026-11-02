-- CQ-A: continuar tras la cuota.

-- Un agente que se paró porque la cuenta de su CLI se quedó sin ventana (5 h o semanal) falla con
-- failure = QUOTA. quota_limited_at es cuando el proveedor rechazó el turno por eso, y quota_window y
-- quota_resets_at, qué ventana y cuándo se renueva, si se sabe.
ALTER TABLE agent_run DROP CONSTRAINT agent_run_failure_check;
ALTER TABLE agent_run ADD CONSTRAINT agent_run_failure_check
    CHECK (failure IN ('LOST', 'START', 'ERROR', 'TIMEOUT', 'BUDGET', 'VERIFICATION', 'QUOTA'));
ALTER TABLE agent_run ADD COLUMN quota_limited_at timestamptz;
ALTER TABLE agent_run ADD COLUMN quota_window text
    CHECK (quota_window IN ('five_hour', 'seven_day'));
ALTER TABLE agent_run ADD COLUMN quota_resets_at timestamptz;

-- Una fase que espera a que se renueve la cuota de un proveedor: un intento nuevo tras un fallo
-- QUOTA o una fase lista que no arranca porque su proveedor ya está a cero. La hora a la que
-- empieza va en not_before.
ALTER TABLE stage_run ADD COLUMN quota_provider text;
ALTER TABLE stage_run ADD COLUMN quota_window text
    CHECK (quota_window IN ('five_hour', 'seven_day'));
ALTER TABLE stage_run ADD COLUMN quota_resets_at timestamptz;
-- Quién pidió «Reintentar ahora»: la fase arranca sin mirar la cuota.
ALTER TABLE stage_run ADD COLUMN quota_skipped_by text;

-- Para saber deprisa qué proveedores se han quedado sin cuota hace poco.
CREATE INDEX agent_run_quota_failure ON agent_run (provider, quota_resets_at) WHERE failure = 'QUOTA';
