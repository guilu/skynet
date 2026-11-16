-- CH-C: cada mensaje del chat con Skyny lo contesta un agente de Claude Code en el runner, con su
-- invocación en agent_run como las de los workflows (cuota, coste, auditoría y cancelación de
-- siempre). Un turno no tiene fase ni repositorio: lleva el rol `chat`.
ALTER TABLE agent_run ALTER COLUMN stage_run_id DROP NOT NULL;
ALTER TABLE agent_run ALTER COLUMN repository_id DROP NOT NULL;

ALTER TABLE agent_run DROP CONSTRAINT agent_run_role_check;
ALTER TABLE agent_run
    ADD CONSTRAINT agent_run_role_check
        CHECK (role IN ('judge', 'skeptic', 'guide', 'eval', 'chat'));

-- Solo un turno del chat va sin fase ni repositorio.
ALTER TABLE agent_run
    ADD CONSTRAINT agent_run_chat_check
        CHECK (role IS NOT DISTINCT FROM 'chat' OR (stage_run_id IS NOT NULL AND repository_id IS NOT NULL));

-- De qué conversación es cada turno, y a qué mensaje contesta.
CREATE TABLE chat_turn (
    agent_run_id     uuid PRIMARY KEY REFERENCES agent_run (id) ON DELETE CASCADE,
    conversation_id  uuid        NOT NULL REFERENCES conversation (id) ON DELETE CASCADE,
    message_id       uuid        NOT NULL REFERENCES conversation_message (id) ON DELETE CASCADE,
    created_at       timestamptz NOT NULL
);
CREATE INDEX chat_turn_conversation ON chat_turn (conversation_id, created_at DESC);
