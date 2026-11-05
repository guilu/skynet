-- DL-A: despliegues de un repositorio en un entorno (Omarchy, ...).
-- Dos ejes separados: el resultado del despliegue (status) y la salud de lo desplegado (health).
-- Que un despliegue termine bien no significa que la aplicación esté sana; eso lo decide DL-C.
CREATE TABLE deployment (
    id                  uuid PRIMARY KEY,
    repository_id       uuid        NOT NULL REFERENCES repository (id) ON DELETE CASCADE,
    environment         text        NOT NULL CHECK (environment ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    -- El id del despliegue en su origen (el de GitHub Deployments en DL-B): la clave de idempotencia.
    external_id         text        NOT NULL CHECK (length(external_id) BETWEEN 1 AND 200),
    commit_sha          text        NOT NULL CHECK (commit_sha ~ '^[0-9a-f]{40}$'),
    ref                 text,
    pull_request_number integer     CHECK (pull_request_number > 0),
    -- Solo se muestra: nunca se usa para comprobar la salud (DL-C configura su propia URL).
    url                 text,
    creator             text,
    status              text        NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'IN_PROGRESS', 'SUCCEEDED', 'FAILED', 'SUPERSEDED')),
    health              text        NOT NULL DEFAULT 'UNKNOWN'
        CHECK (health IN ('UNKNOWN', 'CHECKING', 'HEALTHY', 'UNHEALTHY', 'TIMEOUT')),
    error               text,
    -- Cuándo se creó en su origen: ordena los despliegues de un entorno.
    requested_at        timestamptz NOT NULL,
    started_at          timestamptz,
    finished_at         timestamptz,
    -- La hora del último estado aplicado: un estado más viejo que llega tarde no lo pisa.
    status_at           timestamptz,
    superseded_by       uuid        REFERENCES deployment (id) ON DELETE SET NULL,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    UNIQUE (repository_id, external_id)
);

CREATE INDEX deployment_environment_idx ON deployment (repository_id, environment, requested_at DESC);
CREATE INDEX deployment_sha_idx ON deployment (repository_id, commit_sha);

-- Cada estado recibido, tal como llegó: la historia y la evidencia del despliegue.
CREATE TABLE deployment_status (
    id            uuid PRIMARY KEY,
    deployment_id uuid        NOT NULL REFERENCES deployment (id) ON DELETE CASCADE,
    -- El id del estado en su origen: el mismo estado dos veces se registra una.
    external_id   text        NOT NULL CHECK (length(external_id) BETWEEN 1 AND 200),
    state         text        NOT NULL
        CHECK (state IN ('PENDING', 'IN_PROGRESS', 'SUCCEEDED', 'FAILED')),
    description   text,
    occurred_at   timestamptz NOT NULL,
    -- Si llegó tarde y no cambió el despliegue.
    applied       boolean     NOT NULL,
    received_at   timestamptz NOT NULL,
    UNIQUE (deployment_id, external_id)
);

CREATE INDEX deployment_status_deployment_idx ON deployment_status (deployment_id, occurred_at);
