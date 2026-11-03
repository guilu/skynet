-- VA-C: la verificación adversarial del juez. Cada hallazgo que bloquea pasa por N escépticos de
-- solo lectura que intentan refutarlo; si la mayoría lo refuta, se descarta.

-- 'skeptic' para un escéptico.
ALTER TABLE agent_run DROP CONSTRAINT agent_run_role_check;
ALTER TABLE agent_run
    ADD CONSTRAINT agent_run_role_check CHECK (role IN ('judge', 'skeptic'));

-- REFUTING: los escépticos de la ronda están trabajando.
ALTER TABLE stage_judge DROP CONSTRAINT stage_judge_status_check;
ALTER TABLE stage_judge
    ADD CONSTRAINT stage_judge_status_check
        CHECK (status IN ('CHECKING', 'JUDGING', 'REFUTING', 'FIXING', 'PASSED', 'FAILED'));

-- Si en la última ronda hubo hallazgos sin refutar por los topes, el aviso («Se han refutado 8 de
-- 11 hallazgos…»). Nada de recortes en silencio.
ALTER TABLE stage_judge
    ADD COLUMN refute_note text;

-- Lo que dijo cada escéptico de cada hallazgo, por ronda. `finding` es el orden del hallazgo entre
-- los que se refutan en la ronda, y `content` el hallazgo tal y como lo vio.
CREATE TABLE stage_refutation (
    id              uuid PRIMARY KEY,
    stage_run_id    uuid        NOT NULL REFERENCES stage_run (id),
    workflow_run_id uuid        NOT NULL REFERENCES workflow_run (id),
    round           integer     NOT NULL,
    finding         integer     NOT NULL,
    judge           text        NOT NULL,
    content         jsonb       NOT NULL,
    skeptic         integer     NOT NULL,
    agent_run_id    uuid        NOT NULL REFERENCES agent_run (id),
    status          text        NOT NULL CHECK (status IN ('PENDING', 'DONE', 'FAILED')),
    refuted         boolean,
    reason          text,
    error           text,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    UNIQUE (stage_run_id, round, finding, skeptic)
);

CREATE INDEX stage_refutation_stage ON stage_refutation (stage_run_id, round);
CREATE INDEX stage_refutation_run ON stage_refutation (workflow_run_id);
