-- CH-A: las conversaciones del chat con Skyny (docs/chat.md). Cada una tiene sus mensajes, en
-- orden; el proyecto y el trabajo son el contexto desde el que se abrió y pueden faltar. Si se
-- purga el proyecto o el trabajo, la conversación se queda sin contexto en vez de borrarse.
CREATE TABLE conversation (
    id                  uuid PRIMARY KEY,
    title               text        NOT NULL,
    project_id          uuid REFERENCES project (id) ON DELETE SET NULL,
    work_item_id        uuid REFERENCES work_item (id) ON DELETE SET NULL,
    provider            text        NOT NULL,
    model               text        NOT NULL,
    -- La sesión del proveedor que reanuda cada turno (CH-C); NULL hasta el primero.
    provider_session_id text,
    -- IDLE: nadie está contestando. ANSWERING: hay un turno en marcha (CH-C). OFFLINE: el último
    -- mensaje no tiene quien lo conteste (sin runner, o antes de CH-C).
    state               text        NOT NULL CHECK (state IN ('IDLE', 'ANSWERING', 'OFFLINE')),
    message_count       integer     NOT NULL DEFAULT 0,
    created_at          timestamptz NOT NULL,
    created_by          text        NOT NULL,
    updated_at          timestamptz NOT NULL,
    archived_at         timestamptz
);

CREATE INDEX conversation_recent ON conversation (updated_at DESC);
CREATE INDEX conversation_project ON conversation (project_id);

CREATE TABLE conversation_message (
    id              uuid PRIMARY KEY,
    conversation_id uuid        NOT NULL REFERENCES conversation (id) ON DELETE CASCADE,
    -- Posición en la conversación, desde 1.
    seq             integer     NOT NULL,
    author          text        NOT NULL CHECK (author IN ('USER', 'SKYNY')),
    body            text        NOT NULL,
    -- Tarjetas que acompañan al texto (CH-B, CH-D): [{kind, id, label, url}].
    cards           jsonb       NOT NULL DEFAULT '[]',
    created_at      timestamptz NOT NULL,
    UNIQUE (conversation_id, seq)
);
