-- DS-A: disparadores. Una regla dice qué workflow se puede lanzar desde fuera en un repositorio y
-- con qué; de momento, con una etiqueta en una issue de GitHub (ISSUE). DS-B y DS-C añaden los
-- comentarios /skynet y el cron.
CREATE TABLE trigger_rule (
    id              uuid PRIMARY KEY,
    -- Al purgar el repositorio se van sus reglas.
    repository_id   uuid        NOT NULL REFERENCES repository (id) ON DELETE CASCADE,
    kind            text        NOT NULL CHECK (kind IN ('ISSUE')),
    workflow_key    text        NOT NULL,
    -- La etiqueta que lo lanza, p. ej. skynet:implementar-tarea.
    label           text        NOT NULL,
    work_item_type  text        NOT NULL DEFAULT 'FEATURE',
    -- Datos de entrada fijos: los de la issue no los pueden cambiar.
    inputs          jsonb       NOT NULL DEFAULT '{}'::jsonb,
    -- Los datos que sí puede dar la issue: {"nombre": {"type", "maxLength", "allowedValues"}}.
    input_policy    jsonb       NOT NULL DEFAULT '{}'::jsonb,
    enabled         boolean     NOT NULL DEFAULT true,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL
);

CREATE UNIQUE INDEX trigger_rule_issue_label ON trigger_rule (repository_id, lower(label))
    WHERE kind = 'ISSUE';

-- Cada vez que algo de fuera pide lanzar un workflow. Es también la cola: un worker reclama las
-- QUEUED con FOR UPDATE SKIP LOCKED.
--   QUEUED     recibida, sin decidir todavía
--   LAUNCHED   lanzada: la ejecución está en marcha
--   FINISHED   la ejecución terminó (bien o mal)
--   DUPLICATE  el trabajo ya tenía una ejecución en marcha
--   REJECTED   no se lanzó, con el motivo (sin permiso, interruptor apagado, datos malos…)
-- notice* es el aviso pendiente en GitHub (comentario, etiquetas y reacción), que se manda
-- aparte: si GitHub falla, se reintenta el aviso, nunca el lanzamiento.
CREATE TABLE trigger_launch (
    id               uuid PRIMARY KEY,
    delivery_id      text        NOT NULL,
    source           text        NOT NULL,
    issue_number     bigint      NOT NULL,
    issue_url        text,
    label            text        NOT NULL,
    mode             text        NOT NULL CHECK (mode IN ('ISSUE', 'ORDER')),
    author           text,
    sender           text,
    title            text        NOT NULL,
    body             text        NOT NULL,
    trigger_id       uuid REFERENCES trigger_rule (id) ON DELETE SET NULL,
    -- Al purgar el repositorio, el trabajo o la ejecución, la petición se queda sin ellos.
    repository_id    uuid REFERENCES repository (id) ON DELETE SET NULL,
    status           text        NOT NULL
        CHECK (status IN ('QUEUED', 'LAUNCHED', 'FINISHED', 'DUPLICATE', 'REJECTED')),
    reason           text,
    work_item_id     uuid REFERENCES work_item (id) ON DELETE SET NULL,
    workflow_run_id  uuid REFERENCES workflow_run (id) ON DELETE SET NULL,
    attempts         integer     NOT NULL DEFAULT 0,
    run_after        timestamptz NOT NULL,
    notice           text,
    notice_add       jsonb       NOT NULL DEFAULT '[]'::jsonb,
    notice_remove    jsonb       NOT NULL DEFAULT '[]'::jsonb,
    notice_react     boolean     NOT NULL DEFAULT false,
    notice_attempts  integer     NOT NULL DEFAULT 0,
    notice_after     timestamptz,
    notice_error     text,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL
);

CREATE INDEX trigger_launch_queued ON trigger_launch (run_after) WHERE status = 'QUEUED';
CREATE INDEX trigger_launch_notice ON trigger_launch (notice_after) WHERE notice_after IS NOT NULL;
CREATE INDEX trigger_launch_run ON trigger_launch (workflow_run_id);
CREATE INDEX trigger_launch_created ON trigger_launch (created_at DESC);

-- Una issue es un trabajo: el mismo externalRef de GitHub no se repite en un proyecto.
CREATE UNIQUE INDEX work_item_github_ref ON work_item (project_id, external_ref)
    WHERE external_ref LIKE 'github:%';
