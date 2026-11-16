-- CH-B: las acciones que Skyny propone en una conversación (docs/chat.md, «Acciones por
-- confirmar»). Skyny solo las propone con el token de la conversación; las confirma o las rechaza
-- la persona desde la web, y entonces se hacen con el servicio de siempre.
CREATE TABLE conversation_action (
    id              uuid PRIMARY KEY,
    conversation_id uuid        NOT NULL REFERENCES conversation (id) ON DELETE CASCADE,
    kind            text        NOT NULL CHECK (kind IN ('LAUNCH', 'CREATE_WORK_ITEM', 'CANCEL_RUN',
                                    'RETRY_STAGE', 'DECIDE_APPROVAL', 'SAVE_MEMORY')),
    -- Lo que pide, ya comprobado: {workItemId, workflow, …} según el tipo.
    input           jsonb       NOT NULL,
    -- Cómo se enseña en la tarjeta: «Lanzar implementar-tarea en AKA-1».
    summary         text        NOT NULL,
    -- PENDING: espera a la persona. CONFIRMED: se hizo. REJECTED: no la quiso. EXPIRED: nadie la
    -- confirmó a tiempo. FAILED: se confirmó y el servicio la rechazó.
    status          text        NOT NULL CHECK (status IN ('PENDING', 'CONFIRMED', 'REJECTED',
                                    'EXPIRED', 'FAILED')),
    -- Lo que salió al hacerla: una tarjeta {kind, id, label, url}.
    result          jsonb,
    error           text,
    created_at      timestamptz NOT NULL,
    decided_at      timestamptz,
    decided_by      text
);

CREATE INDEX conversation_action_conversation ON conversation_action (conversation_id, created_at);
