-- ME-B: token de memoria de cada invocación. Lo pide el runner justo antes de lanzar el agente y
-- se lo da al puente MCP de memoria; solo se guarda su sha256. Vale mientras la invocación no ha
-- terminado y solo para el proyecto y el repositorio de esa invocación.
CREATE TABLE memory_token (
    agent_run_id uuid PRIMARY KEY REFERENCES agent_run (id) ON DELETE CASCADE,
    token_hash   text        NOT NULL UNIQUE,
    mode         text        NOT NULL CHECK (mode IN ('READ', 'WRITE')),
    -- Llamadas y guardados de la invocación, para sus límites.
    calls        integer     NOT NULL DEFAULT 0,
    saves        integer     NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL
);
