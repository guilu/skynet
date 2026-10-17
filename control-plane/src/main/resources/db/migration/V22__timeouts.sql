-- Límites de tiempo y cancelación (W6-B).

-- Un intento de una fase con `timeout` tiene que terminar antes de `deadline`, que se fija al
-- pasar a READY. `timed_out_at`: llegó al límite y se le pararon los agentes.
ALTER TABLE stage_run
    ADD COLUMN deadline     timestamptz,
    ADD COLUMN timed_out_at timestamptz;

-- Se paró el agente porque su fase llegó al límite: termina FAILED con `failure = TIMEOUT` en vez
-- de CANCELLED.
ALTER TABLE agent_run ADD COLUMN timed_out_at timestamptz;

-- Se pidió cancelar la ejecución; mientras sus agentes no confirman, está «Cancelando».
ALTER TABLE workflow_run ADD COLUMN cancel_requested_at timestamptz;

-- Una puerta con `timeout` caduca: la petición queda EXPIRED, firmada por `skynet`.
ALTER TABLE approval_request DROP CONSTRAINT approval_request_status_check;
ALTER TABLE approval_request ADD CONSTRAINT approval_request_status_check
    CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CHANGES_REQUESTED', 'CANCELLED',
                      'EXPIRED'));

ALTER TABLE approval_decision DROP CONSTRAINT approval_decision_action_check;
ALTER TABLE approval_decision ADD CONSTRAINT approval_decision_action_check
    CHECK (action IN ('APPROVE', 'REJECT', 'REQUEST_CHANGES', 'COMMENT', 'CANCEL', 'EXPIRE'));
