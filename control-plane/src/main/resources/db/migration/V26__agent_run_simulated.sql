-- AR-A: la invocación se ejecutó con un CLI simulado (fake-claude, fake-codex…) y no con el real.
-- Se fija al empezar su sesión, con la versión que declaraba el runner: así una ejecución antigua
-- sigue diciendo con qué se hizo aunque el runner cambie de CLI.
ALTER TABLE agent_run ADD COLUMN simulated boolean NOT NULL DEFAULT false;
