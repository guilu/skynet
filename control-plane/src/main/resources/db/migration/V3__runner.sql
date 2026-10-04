-- Runners y su cola de órdenes (docs/implementation-plan.md §4.3). La tabla workspace llega con
-- el WorkspaceManager del runner, que es quien la rellena.

CREATE TABLE runner (
    id                 uuid PRIMARY KEY,
    name               text        NOT NULL UNIQUE,
    -- sha256 del token; el token solo se muestra al registrar.
    token_hash         text        NOT NULL UNIQUE,
    capacity           integer     NOT NULL CHECK (capacity > 0),
    runner_version     text,
    provider_version   text,
    registered_at      timestamptz NOT NULL,
    last_heartbeat_at  timestamptz
);

-- PENDING → DELIVERED → ACKED, o CANCELLED si deja de tener sentido antes de entregarse. Un START
-- pendiente no tiene runner: lo reclama el primero con capacidad libre. Lo entregado y no
-- confirmado se vuelve a entregar al mismo runner.
CREATE TABLE runner_command (
    id                 uuid PRIMARY KEY,
    runner_id          uuid REFERENCES runner (id),
    agent_run_id       uuid        NOT NULL REFERENCES agent_run (id),
    type               text        NOT NULL,
    payload            jsonb,
    status             text        NOT NULL,
    created_at         timestamptz NOT NULL,
    delivered_at       timestamptz,
    acked_at           timestamptz,
    delivery_count     integer     NOT NULL DEFAULT 0
);
CREATE INDEX runner_command_pending_idx ON runner_command (status, runner_id, created_at);
CREATE INDEX runner_command_agent_run_idx ON runner_command (agent_run_id);

ALTER TABLE agent_run
    ADD CONSTRAINT agent_run_runner_fk FOREIGN KEY (runner_id) REFERENCES runner (id),
    ADD COLUMN cancel_requested_at timestamptz,
    -- Resultado que declara el proveedor (línea `result`); el estado final se decide al terminar
    -- el proceso, porque una cancelación no deja resultado.
    ADD COLUMN result_subtype      text,
    ADD COLUMN result_is_error     boolean,
    -- El CLI da el coste con más de 6 decimales; con 6 la diferencia entre invocaciones de una
    -- misma sesión acumula errores de redondeo.
    ALTER COLUMN cost_usd TYPE numeric(20, 10),
    ALTER COLUMN cost_usd_cumulative TYPE numeric(20, 10);
CREATE INDEX agent_run_runner_idx ON agent_run (runner_id) WHERE runner_id IS NOT NULL;
