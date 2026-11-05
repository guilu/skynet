-- DL-C: la salud de lo desplegado, comprobada por Skynet, y los despliegues atascados.

-- Cómo se comprueba la salud de un entorno de un repositorio. La URL la pone el admin: nunca se
-- toma de un evento, para que nadie de fuera pueda hacer que Skynet llame a otra dirección.
CREATE TABLE deployment_health_check (
    repository_id         uuid        NOT NULL REFERENCES repository (id) ON DELETE CASCADE,
    environment           text        NOT NULL CHECK (environment ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    url                   text        NOT NULL CHECK (url ~ '^https?://[^\s@/]+(/[^\s#]*)?$'),
    timeout_seconds       integer     NOT NULL DEFAULT 5 CHECK (timeout_seconds BETWEEN 1 AND 60),
    attempts              integer     NOT NULL DEFAULT 6 CHECK (attempts BETWEEN 1 AND 30),
    interval_seconds      integer     NOT NULL DEFAULT 10 CHECK (interval_seconds BETWEEN 1 AND 600),
    initial_delay_seconds integer     NOT NULL DEFAULT 15 CHECK (initial_delay_seconds BETWEEN 0 AND 600),
    -- Si la respuesta tiene que contener el SHA desplegado (los 7 primeros caracteres basta).
    expect_sha            boolean     NOT NULL DEFAULT false,
    enabled               boolean     NOT NULL DEFAULT true,
    created_at            timestamptz NOT NULL,
    updated_at            timestamptz NOT NULL,
    PRIMARY KEY (repository_id, environment)
);

ALTER TABLE deployment ADD COLUMN health_detail text;
ALTER TABLE deployment ADD COLUMN health_checked_at timestamptz;
-- Cuándo toca el siguiente intento; NULL si no hay comprobación en curso. Al reclamar un intento
-- se adelanta como plazo: si Skynet se cae a mitad, otro lo retoma al vencer.
ALTER TABLE deployment ADD COLUMN health_next_at timestamptz;
ALTER TABLE deployment ADD COLUMN health_attempts integer NOT NULL DEFAULT 0;
-- Cuándo se dio por atascado: sin estado final pasado el plazo. Se borra con el siguiente estado.
ALTER TABLE deployment ADD COLUMN stuck_at timestamptz;

CREATE INDEX deployment_health_due ON deployment (health_next_at) WHERE health_next_at IS NOT NULL;
CREATE INDEX deployment_unfinished ON deployment (status_at)
    WHERE status IN ('PENDING', 'IN_PROGRESS') AND stuck_at IS NULL;

-- Cada intento de comprobación: la evidencia.
CREATE TABLE deployment_health_probe (
    id            uuid PRIMARY KEY,
    deployment_id uuid        NOT NULL REFERENCES deployment (id) ON DELETE CASCADE,
    attempt       integer     NOT NULL,
    url           text        NOT NULL,
    outcome       text        NOT NULL CHECK (outcome IN ('OK', 'BAD_STATUS', 'SHA_MISMATCH', 'TIMEOUT', 'ERROR')),
    status_code   integer,
    latency_ms    integer,
    detail        text,
    checked_at    timestamptz NOT NULL
);

CREATE INDEX deployment_health_probe_deployment_idx ON deployment_health_probe (deployment_id, checked_at);
