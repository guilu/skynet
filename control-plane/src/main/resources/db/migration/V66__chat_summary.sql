-- CH-F: el resumen de una conversación a la memoria del proyecto (docs/chat.md «Resumen a la
-- memoria»). Un turno del chat contesta a un mensaje (answer) o resume la conversación (summary);
-- summarized_count es hasta qué mensaje se resumió, para no resumir dos veces lo mismo.
ALTER TABLE chat_turn
    ADD COLUMN purpose text NOT NULL DEFAULT 'answer' CHECK (purpose IN ('answer', 'summary'));

ALTER TABLE conversation ADD COLUMN summarized_count integer NOT NULL DEFAULT 0;

CREATE INDEX conversation_to_summarize ON conversation (updated_at)
    WHERE project_id IS NOT NULL AND archived_at IS NULL AND state = 'IDLE';
