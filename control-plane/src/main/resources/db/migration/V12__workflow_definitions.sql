-- W1-A: definiciones de workflow con versiones. Un workflow (su clave) tiene versiones numeradas;
-- cada una es un borrador (DRAFT si tiene errores, VALIDATED si no) hasta que se publica
-- (PUBLISHED), y entonces ya no cambia. Como mucho hay un borrador por workflow.
CREATE TABLE workflow (
    id               uuid PRIMARY KEY,
    key              text        NOT NULL UNIQUE CHECK (key ~ '^[a-z][a-z0-9-]{1,48}$'),
    created_at       timestamptz NOT NULL,
    archived_at      timestamptz
);

INSERT INTO workflow (id, key, created_at)
SELECT gen_random_uuid(), key, min(created_at) FROM workflow_definition GROUP BY key;

-- adhoc pasa a pedir el prompt como un dato más, para que el motor (W2) lo lance como cualquier
-- otro workflow. Ninguna ejecución depende del texto: solo de su id.
UPDATE workflow_definition SET source_yaml =
E'id: adhoc\nversion: 1\nname: Agente suelto\ndescription: Una fase con un agente y el prompt que escribes al lanzar.\ninputs:\n  prompt:\n    type: text\n    required: true\n    description: Qué tiene que hacer el agente.\nstages:\n  - id: agent\n    type: agent\n    prompt: "{{inputs.prompt}}"\n'
WHERE id = '00000000-0000-0000-0000-000000000001';

ALTER TABLE workflow_definition
    ADD COLUMN status       text NOT NULL DEFAULT 'PUBLISHED'
        CHECK (status IN ('DRAFT', 'VALIDATED', 'PUBLISHED')),
    ADD COLUMN name         text,
    ADD COLUMN description  text,
    ADD COLUMN updated_at   timestamptz,
    ADD COLUMN published_at timestamptz,
    -- Control de concurrencia de los borradores: cada guardado lo incrementa.
    ADD COLUMN revision     bigint NOT NULL DEFAULT 0,
    ADD CONSTRAINT workflow_definition_workflow_fk FOREIGN KEY (key) REFERENCES workflow (key);

UPDATE workflow_definition SET updated_at = created_at, published_at = created_at;
UPDATE workflow_definition SET name = 'Agente suelto',
    description = 'Una fase con un agente y el prompt que escribes al lanzar.'
WHERE id = '00000000-0000-0000-0000-000000000001';

ALTER TABLE workflow_definition
    ALTER COLUMN status DROP DEFAULT,
    ALTER COLUMN updated_at SET NOT NULL,
    ADD CONSTRAINT workflow_definition_published_check
        CHECK ((status = 'PUBLISHED') = (published_at IS NOT NULL));

CREATE UNIQUE INDEX workflow_definition_one_draft_idx ON workflow_definition (key)
    WHERE status <> 'PUBLISHED';

-- Una versión publicada no se modifica ni se borra, salvo dentro de una purga (AE-B).
CREATE FUNCTION workflow_definition_is_immutable() RETURNS trigger AS $$
BEGIN
    IF OLD.status = 'PUBLISHED' THEN
        IF TG_OP = 'DELETE' AND current_setting('skynet.purge', true) = 'on' THEN
            RETURN OLD;
        END IF;
        RAISE EXCEPTION 'una versión publicada de un workflow no se modifica';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER workflow_definition_immutable
    BEFORE UPDATE OR DELETE ON workflow_definition
    FOR EACH ROW EXECUTE FUNCTION workflow_definition_is_immutable();
