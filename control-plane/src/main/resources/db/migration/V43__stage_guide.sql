-- VA-D: la guía de pruebas como fase. Un agente ejecuta, en una copia del worktree, la guía que
-- trae el cambio, paso a paso; se lleva como un juez de un solo agente (stage_judge, stage_verdict)
-- con sus mismas rondas de corrección.

-- 'guide' para quien ejecuta la guía.
ALTER TABLE agent_run DROP CONSTRAINT agent_run_role_check;
ALTER TABLE agent_run
    ADD CONSTRAINT agent_run_role_check CHECK (role IN ('judge', 'skeptic', 'guide'));

-- Lo que contó del recorrido de la guía: {guides: [...], steps: [{guide, section, step, status,
-- evidence}], summary}. NULL en un juez con rúbrica.
ALTER TABLE stage_verdict
    ADD COLUMN guide jsonb;
