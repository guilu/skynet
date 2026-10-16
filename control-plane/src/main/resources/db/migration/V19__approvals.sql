-- W5-A: puertas de aprobación. Una fase `human-approval` espera en WAITING_FOR_APPROVAL hasta que
-- alguien decide; cada vez que pregunta deja una petición (una ronda) y cada decisión o comentario
-- queda registrado sin que se pueda reescribir.

CREATE TABLE approval_request (
    id               uuid PRIMARY KEY,
    workflow_run_id  uuid        NOT NULL REFERENCES workflow_run (id),
    stage_run_id     uuid        NOT NULL REFERENCES stage_run (id),
    -- 1 la primera vez; una más cada vez que vuelve a preguntar tras pedir cambios (W5-B).
    round            integer     NOT NULL,
    action           text        NOT NULL,
    risk             text        NOT NULL CHECK (risk IN ('LOW', 'MEDIUM', 'HIGH')),
    -- Foto de lo que se enseña al decidir, tomada al crear la petición: origen, evidencia de las
    -- dependencias, permisos que se conceden y consecuencias.
    evidence         jsonb       NOT NULL,
    status           text        NOT NULL
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CHANGES_REQUESTED', 'CANCELLED')),
    created_at       timestamptz NOT NULL,
    decided_at       timestamptz,
    UNIQUE (stage_run_id, round),
    CHECK ((status = 'PENDING') = (decided_at IS NULL))
);

CREATE INDEX approval_request_pending_idx ON approval_request (created_at)
    WHERE status = 'PENDING';
CREATE INDEX approval_request_run_idx ON approval_request (workflow_run_id);

CREATE TABLE approval_decision (
    id                   uuid PRIMARY KEY,
    approval_request_id  uuid        NOT NULL REFERENCES approval_request (id),
    action               text        NOT NULL
        CHECK (action IN ('APPROVE', 'REJECT', 'REQUEST_CHANGES', 'COMMENT', 'CANCEL')),
    comment              text,
    -- Quién decide: el usuario de la web, o `skynet` cuando la petición se cancela con su ejecución.
    actor                text        NOT NULL,
    created_at           timestamptz NOT NULL
);

CREATE INDEX approval_decision_request_idx ON approval_decision (approval_request_id, created_at);

-- Las decisiones no se modifican ni se borran, salvo dentro de una purga (AE-B).
CREATE FUNCTION approval_decision_is_append_only() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' AND current_setting('skynet.purge', true) = 'on' THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'una decisión de aprobación no se modifica ni se borra';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER approval_decision_append_only
    BEFORE UPDATE OR DELETE ON approval_decision
    FOR EACH ROW EXECUTE FUNCTION approval_decision_is_append_only();

-- Una petición decidida no cambia: solo una pendiente pasa a su estado final.
CREATE FUNCTION approval_request_is_final() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF current_setting('skynet.purge', true) = 'on' THEN
            RETURN OLD;
        END IF;
        RAISE EXCEPTION 'una petición de aprobación no se borra';
    END IF;
    IF OLD.status <> 'PENDING' THEN
        RAISE EXCEPTION 'una petición de aprobación decidida no se modifica';
    END IF;
    IF NEW.id <> OLD.id OR NEW.stage_run_id <> OLD.stage_run_id OR NEW.round <> OLD.round
        OR NEW.action <> OLD.action OR NEW.risk <> OLD.risk OR NEW.evidence <> OLD.evidence
        OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'de una petición de aprobación solo cambia su estado';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER approval_request_final
    BEFORE UPDATE OR DELETE ON approval_request
    FOR EACH ROW EXECUTE FUNCTION approval_request_is_final();
