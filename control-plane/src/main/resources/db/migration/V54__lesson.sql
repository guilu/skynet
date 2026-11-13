-- AP-B: lecciones. Cuando una clase de hallazgo se repite en un repositorio (en ejecuciones
-- distintas), Skynet propone convertirla en algo que se aplique solo: `prevenir` (lo corregido,
-- como restricción fijada en la memoria) o `no-senalar` (lo que se dio por bueno, como criterio
-- del juez). No se aplica nada sin que una persona lo decida.
CREATE TABLE lesson (
    id             uuid PRIMARY KEY,
    project_id     uuid        NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    repository_id  uuid        NOT NULL REFERENCES repository (id) ON DELETE CASCADE,
    category       text        NOT NULL,
    kind           text        NOT NULL CHECK (kind IN ('prevenir', 'no-senalar')),
    status         text        NOT NULL CHECK (status IN ('propuesta', 'aplicada', 'descartada')),
    -- Lo que propone Skynet (una plantilla) y lo que se aplicó (quizá editado).
    suggested_text text        NOT NULL,
    applied_text   text,
    -- En cuántas ejecuciones distintas ha salido.
    runs           integer     NOT NULL,
    -- La restricción fijada en la memoria, si se aplicó una de `prevenir`.
    observation_id uuid REFERENCES memory_observation (id) ON DELETE SET NULL,
    decided_by     text,
    decided_at     timestamptz,
    reason         text,
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL,
    UNIQUE (repository_id, category, kind)
);

CREATE INDEX lesson_project ON lesson (project_id, status, updated_at DESC);

-- Los hallazgos que la justifican.
CREATE TABLE lesson_finding (
    lesson_id  uuid NOT NULL REFERENCES lesson (id) ON DELETE CASCADE,
    finding_id uuid NOT NULL REFERENCES finding (id) ON DELETE CASCADE,
    PRIMARY KEY (lesson_id, finding_id)
);
