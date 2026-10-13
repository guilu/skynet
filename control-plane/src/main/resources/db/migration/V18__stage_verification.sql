-- W4-B: verificación de fase. Una fase con `artifacts` o `checks` sigue en marcha cuando su agente
-- termina, hasta que el runner comprueba su worktree; si algo no pasa, la fase falla.

-- Origen STAGE (además de AUTO y MANUAL): la verificación de una fase, con sus comprobaciones.
ALTER TABLE verification_run
    ADD COLUMN stage_run_id uuid REFERENCES stage_run (id),
    -- Las comprobaciones pedidas y, al terminar, su resultado: [{name, target, passed, reason}].
    ADD COLUMN checks       jsonb;
-- Una fase `verification` o sin `tests` no ejecuta el comando del repositorio.
ALTER TABLE verification_run ALTER COLUMN command DROP NOT NULL;

CREATE INDEX verification_run_stage_idx ON verification_run (stage_run_id)
    WHERE stage_run_id IS NOT NULL;

-- El HEAD del worktree al empezar la invocación: la base de `diff`, `commit` y `scope`.
ALTER TABLE agent_run ADD COLUMN base_commit text;
