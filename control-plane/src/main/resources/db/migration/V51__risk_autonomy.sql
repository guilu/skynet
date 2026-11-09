-- AU-C: revisión y fusión según el riesgo.

-- El nivel de riesgo del cambio cuando entran los jueces de la ronda (NULL si no se midió), y
-- SKIPPED: el cambio no llega a `judge.minRisk` y la fase pasa sin juez.
ALTER TABLE stage_judge ADD COLUMN risk smallint CHECK (risk BETWEEN 0 AND 2);
ALTER TABLE stage_judge ADD COLUMN min_risk smallint;
ALTER TABLE stage_judge DROP CONSTRAINT stage_judge_status_check;
ALTER TABLE stage_judge
    ADD CONSTRAINT stage_judge_status_check
        CHECK (status IN ('CHECKING', 'JUDGING', 'REFUTING', 'FIXING', 'PASSED', 'FAILED',
                          'SKIPPED'));

-- REFUTING: los escépticos intentan refutar lo que encontraron los jueces del delta (AU-B).
ALTER TABLE judged_delta DROP CONSTRAINT judged_delta_status_check;
ALTER TABLE judged_delta
    ADD CONSTRAINT judged_delta_status_check
        CHECK (status IN ('UNCHANGED', 'SKIPPED', 'JUDGING', 'REFUTING', 'PASSED', 'FAILED'));

-- El interruptor por repositorio: si las puertas con `autoApprove` pueden aprobarse solas.
-- Apagado por defecto.
ALTER TABLE repository ADD COLUMN auto_merge boolean NOT NULL DEFAULT false;

-- Las aprobaciones que dio Skynet solo, para el freno diario por repositorio.
CREATE INDEX approval_decision_auto ON approval_decision (created_at) WHERE actor = 'skynet';
