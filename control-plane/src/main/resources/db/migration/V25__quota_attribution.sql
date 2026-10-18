-- Atribución de la cuota (CU-B). Entre dos muestras seguidas de una misma ventana, la subida
-- (delta, en fracción de la ventana) se reparte entre los agentes de ese CLI que corrieron en ese
-- intervalo. Solo se guardan los intervalos en los que la ventana subió.
-- renewed: la ventana se renovó entre las dos muestras y la subida se cuenta desde 0.
-- skynet: la parte de delta atribuida a agentes de Skynet; el resto es «Fuera de Skynet».
CREATE TABLE quota_interval (
    id            bigserial   PRIMARY KEY,
    runner_id     uuid        NOT NULL REFERENCES runner (id) ON DELETE CASCADE,
    provider      text        NOT NULL,
    bucket        text        NOT NULL,
    window_kind   text        NOT NULL CHECK (window_kind IN ('five_hour', 'seven_day')),
    started_at    timestamptz NOT NULL,
    ended_at      timestamptz NOT NULL CHECK (ended_at >= started_at),
    delta         double precision NOT NULL CHECK (delta > 0 AND delta <= 1),
    skynet        double precision NOT NULL CHECK (skynet >= 0),
    renewed       boolean     NOT NULL
);

CREATE INDEX quota_interval_window
    ON quota_interval (runner_id, provider, bucket, window_kind, ended_at);
CREATE INDEX quota_interval_ended ON quota_interval (ended_at);

-- La parte de un intervalo que se atribuye a un agente. method: 'measured' si fue el único agente
-- del intervalo, 'prorated' si se repartió entre varios. Se queda aunque se borre el intervalo
-- (a los 35 días, con sus muestras): la ejecución sigue diciendo cuánto gastó.
CREATE TABLE agent_run_quota (
    id            bigserial   PRIMARY KEY,
    interval_id   bigint      REFERENCES quota_interval (id) ON DELETE SET NULL,
    agent_run_id  uuid        NOT NULL REFERENCES agent_run (id) ON DELETE CASCADE,
    provider      text        NOT NULL,
    bucket        text        NOT NULL,
    window_kind   text        NOT NULL CHECK (window_kind IN ('five_hour', 'seven_day')),
    share         double precision NOT NULL CHECK (share >= 0 AND share <= 1),
    method        text        NOT NULL CHECK (method IN ('measured', 'prorated')),
    ended_at      timestamptz NOT NULL
);

CREATE INDEX agent_run_quota_agent ON agent_run_quota (agent_run_id);
CREATE INDEX agent_run_quota_interval ON agent_run_quota (interval_id);
