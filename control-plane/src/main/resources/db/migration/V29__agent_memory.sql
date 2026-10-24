-- ME-C: acceso de cada invocación a la memoria del proyecto, según la clave `memory` de su fase.
-- NULL: sin memoria (un comando, una fase con `memory: off` o una invocación anterior a ME-C).
ALTER TABLE agent_run
    ADD COLUMN memory text CHECK (memory IN ('READ', 'WRITE'));

-- Las observaciones que recibió una invocación en el bloque de contexto inicial del prompt.
ALTER TABLE memory_access DROP CONSTRAINT memory_access_via_check;
ALTER TABLE memory_access
    ADD CONSTRAINT memory_access_via_check CHECK (via IN ('PROMPT', 'CONTEXT', 'SEARCH', 'GET', 'SAVE'));
