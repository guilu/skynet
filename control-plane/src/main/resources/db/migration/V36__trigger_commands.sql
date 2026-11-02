-- DS-B: las órdenes /skynet en comentarios y las revisiones que piden cambios. Una orden que no
-- lanza nada (corrige, cancela, estado) queda en modo COMMAND y termina en DONE o REJECTED.
ALTER TABLE trigger_launch DROP CONSTRAINT trigger_launch_mode_check;
ALTER TABLE trigger_launch ADD CONSTRAINT trigger_launch_mode_check
    CHECK (mode IN ('ISSUE', 'ORDER', 'COMMAND'));
ALTER TABLE trigger_launch DROP CONSTRAINT trigger_launch_status_check;
ALTER TABLE trigger_launch ADD CONSTRAINT trigger_launch_status_check
    CHECK (status IN ('QUEUED', 'LAUNCHED', 'FINISHED', 'DUPLICATE', 'REJECTED', 'DONE'));
-- El comentario de la orden, para reaccionar a él, y la orden tal cual (su primera línea).
ALTER TABLE trigger_launch ADD COLUMN comment_id bigint;
ALTER TABLE trigger_launch ADD COLUMN command text;
-- Si es de una PR (las órdenes de una PR buscan su ejecución por la PR, no por la issue).
ALTER TABLE trigger_launch ADD COLUMN pull_request boolean NOT NULL DEFAULT false;
