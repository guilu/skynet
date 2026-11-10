-- RH-A: el registro de hallazgos. Cada hallazgo de un juez queda con su huella (repositorio,
-- fichero, lente y palabras del texto, sin la línea, que se mueve) y su desenlace. Dentro de una
-- fase, el mismo hallazgo en rondas seguidas es una sola fila (`repeats` cuenta las vueltas);
-- entre ejecuciones, `first_seen_id` apunta a la primera vez que salió en el repositorio.
CREATE TABLE finding (
    id              uuid PRIMARY KEY,
    repository_id   uuid REFERENCES repository (id),
    workflow_run_id uuid        NOT NULL REFERENCES workflow_run (id),
    stage_run_id    uuid        NOT NULL REFERENCES stage_run (id),
    round           integer     NOT NULL,
    source          text        NOT NULL CHECK (source IN ('judge', 'delta')),
    judge           text        NOT NULL,
    lens            text,
    agent_run_id    uuid REFERENCES agent_run (id),
    provider        text,
    model           text,
    file            text,
    line            integer,
    severity        text        NOT NULL,
    text            text        NOT NULL,
    words           text[]      NOT NULL,
    fingerprint     text        NOT NULL,
    outcome         text        NOT NULL CHECK (outcome IN ('abierto', 'refutado', 'corregido',
                                                            'aparcado', 'aceptado', 'irrelevante')),
    outcome_by      text,
    outcome_reason  text,
    outcome_at      timestamptz,
    repeats         integer     NOT NULL DEFAULT 0,
    first_seen_id   uuid REFERENCES finding (id),
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL
);

CREATE INDEX finding_stage ON finding (stage_run_id, round);
CREATE INDEX finding_run ON finding (workflow_run_id, created_at);
CREATE INDEX finding_repository_file ON finding (repository_id, file);
CREATE INDEX finding_repository_fingerprint ON finding (repository_id, fingerprint);
CREATE INDEX finding_created ON finding (created_at);
