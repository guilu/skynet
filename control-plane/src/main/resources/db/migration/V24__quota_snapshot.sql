-- Cuotas de uso de los CLIs (CU-A): cada muestra de una ventana (5 h o semanal) de la cuenta de un
-- CLI en un runner. Los CLIs solo dan porcentajes: used_fraction va de 0 a 1.
-- source: 'invocation' (vista durante un agente, con agent_run_id) o 'poll' (consulta del runner).
CREATE TABLE quota_snapshot (
    id            bigserial   PRIMARY KEY,
    runner_id     uuid        NOT NULL REFERENCES runner (id) ON DELETE CASCADE,
    provider      text        NOT NULL,
    bucket        text        NOT NULL,
    label         text,
    window_kind   text        NOT NULL CHECK (window_kind IN ('five_hour', 'seven_day')),
    used_fraction double precision NOT NULL CHECK (used_fraction BETWEEN 0 AND 1),
    resets_at     timestamptz,
    plan          text,
    source        text        NOT NULL CHECK (source IN ('invocation', 'poll')),
    agent_run_id  uuid        REFERENCES agent_run (id) ON DELETE SET NULL,
    observed_at   timestamptz NOT NULL
);

CREATE INDEX quota_snapshot_latest
    ON quota_snapshot (runner_id, provider, bucket, window_kind, observed_at DESC);
CREATE INDEX quota_snapshot_observed ON quota_snapshot (observed_at);
