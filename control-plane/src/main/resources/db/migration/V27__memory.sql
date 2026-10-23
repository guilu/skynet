-- ME-A: memoria de proyecto (docs/memoria.md). Observaciones al estilo engram: las escriben los
-- agentes (y las personas) mientras trabajan; las personas las revisan a posteriori. Es estado
-- mutable y versionado, aparte del historial append-only: cada versión queda en
-- memory_observation_revision.
CREATE TABLE memory_observation (
    id                     uuid PRIMARY KEY,
    project_id             uuid        NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    -- Sin repositorio, la observación es de todo el proyecto.
    repository_id          uuid REFERENCES repository (id) ON DELETE CASCADE,
    kind                   text        NOT NULL CHECK (kind IN ('DECISION', 'BUGFIX', 'DISCOVERY',
                               'PATTERN', 'CONFIG', 'PITFALL', 'CONSTRAINT', 'SESSION_SUMMARY',
                               'RUN_LOG')),
    title                  text        NOT NULL,
    -- Tema estable (p. ej. arquitectura/auth): guardar otra vez el mismo tema crea una versión.
    topic_key              text,
    what                   text        NOT NULL,
    why                    text,
    location               text,
    learned                text,
    tags                   text[]      NOT NULL DEFAULT '{}',
    origin                 text        NOT NULL CHECK (origin IN ('AGENT', 'HUMAN', 'SYSTEM')),
    source_workflow_run_id uuid REFERENCES workflow_run (id) ON DELETE SET NULL,
    source_agent_run_id    uuid REFERENCES agent_run (id) ON DELETE SET NULL,
    pinned                 boolean     NOT NULL DEFAULT false,
    -- Se enmascaró algún secreto al guardarla.
    redacted               boolean     NOT NULL DEFAULT false,
    version                integer     NOT NULL,
    created_at             timestamptz NOT NULL,
    updated_at             timestamptz NOT NULL,
    updated_by             text,
    archived_at            timestamptz,
    search                 tsvector    NOT NULL
);

CREATE INDEX memory_observation_scope ON memory_observation (project_id, repository_id, updated_at DESC);
CREATE INDEX memory_observation_search ON memory_observation USING gin (search);
-- Un tema vivo por ámbito; uno archivado no impide empezarlo de nuevo.
CREATE UNIQUE INDEX memory_observation_topic ON memory_observation
    (project_id, coalesce(repository_id, '00000000-0000-0000-0000-000000000000'), topic_key)
    WHERE topic_key IS NOT NULL AND archived_at IS NULL;

-- Todas las versiones de cada observación, también la actual.
CREATE TABLE memory_observation_revision (
    observation_id      uuid        NOT NULL REFERENCES memory_observation (id) ON DELETE CASCADE,
    version             integer     NOT NULL,
    kind                text        NOT NULL,
    title               text        NOT NULL,
    topic_key           text,
    what                text        NOT NULL,
    why                 text,
    location            text,
    learned             text,
    tags                text[]      NOT NULL,
    origin              text        NOT NULL,
    source_agent_run_id uuid REFERENCES agent_run (id) ON DELETE SET NULL,
    changed_by          text,
    changed_at          timestamptz NOT NULL,
    PRIMARY KEY (observation_id, version)
);

-- Qué observaciones (y en qué versión) recibió, buscó, leyó o guardó cada invocación (ME-B, ME-C).
CREATE TABLE memory_access (
    id             bigserial PRIMARY KEY,
    agent_run_id   uuid        NOT NULL REFERENCES agent_run (id) ON DELETE CASCADE,
    observation_id uuid        NOT NULL REFERENCES memory_observation (id) ON DELETE CASCADE,
    version        integer     NOT NULL,
    via            text        NOT NULL CHECK (via IN ('CONTEXT', 'SEARCH', 'GET', 'SAVE')),
    accessed_at    timestamptz NOT NULL
);

CREATE INDEX memory_access_agent_run ON memory_access (agent_run_id);
