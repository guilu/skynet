-- CH-B: el token con el que el MCP de Skynet (skynet-runner skynet-mcp) consulta la API en nombre
-- de una conversación (docs/chat.md, «El MCP de Skynet»). Solo se guarda su hash. Vale para leer
-- una lista cerrada de rutas GET y caduca; el turno de Skyny (CH-C) lo pide al empezar y lo
-- revoca al terminar.
CREATE TABLE conversation_token (
    conversation_id uuid PRIMARY KEY REFERENCES conversation (id) ON DELETE CASCADE,
    token_hash      text        NOT NULL UNIQUE,
    -- Llamadas hechas con el token, para su límite.
    calls           integer     NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL,
    expires_at      timestamptz NOT NULL
);
