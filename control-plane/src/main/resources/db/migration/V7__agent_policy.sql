-- Política de los agentes por repositorio (M6-B). Sin política propia (agent_policy_custom
-- falso), el repositorio usa los valores globales de skynet.agent y estas columnas se ignoran.
ALTER TABLE repository
    ADD COLUMN agent_policy_custom    boolean NOT NULL DEFAULT false,
    -- Herramientas permitidas (--allowedTools); con dontAsk, el resto se deniega.
    ADD COLUMN agent_allowed_tools    text[]  NOT NULL DEFAULT '{}',
    ADD COLUMN agent_permission_mode  text,
    -- Variables adicionales que recibe el agente, dentro de las que permite el runner
    -- (SKYNET_AGENT_ENV). NULL: todas las que permite.
    ADD COLUMN agent_env              text[],
    -- Máximos de cada lanzamiento: al lanzar se pueden bajar, no subir. NULL: sin límite.
    ADD COLUMN agent_max_turns        integer,
    ADD COLUMN agent_max_budget_usd   numeric(10, 2),
    ADD COLUMN agent_timeout_minutes  integer;
