-- PV-A: proveedores de agentes que tiene cada runner (Claude Code, Codex, Gemini…). El runner los
-- declara al registrarse y en cada latido; un START solo se entrega a un runner que tenga el
-- proveedor del agente.
CREATE TABLE runner_provider (
    runner_id uuid NOT NULL REFERENCES runner (id) ON DELETE CASCADE,
    provider  text NOT NULL,
    version   text,
    PRIMARY KEY (runner_id, provider)
);

-- Los runners anteriores a esta lista solo sabían ejecutar Claude Code.
INSERT INTO runner_provider (runner_id, provider, version)
SELECT id, 'claude-code', provider_version FROM runner;
