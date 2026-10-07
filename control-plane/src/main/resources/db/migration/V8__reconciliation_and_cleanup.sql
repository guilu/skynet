-- M6-C: reconciliación de invocaciones perdidas y limpieza de worktrees.

-- Latidos seguidos en los que el runner no ha declarado una invocación que confirmó (ACKED):
-- al llegar al umbral, el agente se da por perdido.
ALTER TABLE runner_command
    ADD COLUMN missed_heartbeats integer NOT NULL DEFAULT 0;

-- Un worktree eliminado se conserva como registro (su rama sigue en el repositorio), pero ya no
-- se puede reanudar, bifurcar ni verificar.
ALTER TABLE workspace
    ADD COLUMN cleanup_requested_at timestamptz,
    ADD COLUMN cleanup_error        text,
    ADD COLUMN removed_at           timestamptz;
