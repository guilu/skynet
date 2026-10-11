-- W3-B: fases parallel. Una fase puede tener varios agentes a la vez, uno por hijo; cada uno guarda
-- qué hijo ejecuta. En las demás fases queda NULL.
ALTER TABLE agent_run ADD COLUMN child_key text;
