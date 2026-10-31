-- GH-A: la GitHub App de esta instalación de Skynet. Las instalaciones y sus repositorios se
-- rellenan al sincronizar (y, desde GH-B, con los webhooks installation*). La clave privada y los
-- tokens nunca se guardan aquí.
CREATE TABLE github_installation (
    id                    bigint PRIMARY KEY,
    account_login         text        NOT NULL,
    account_type          text        NOT NULL,
    -- all: todos los repositorios de la cuenta; selected: solo los elegidos al instalar.
    repository_selection  text        NOT NULL,
    suspended_at          timestamptz,
    synced_at             timestamptz NOT NULL,
    created_at            timestamptz NOT NULL
);

CREATE TABLE github_installation_repository (
    installation_id  bigint  NOT NULL REFERENCES github_installation (id) ON DELETE CASCADE,
    repository_id    bigint  NOT NULL,
    -- owner/nombre tal como lo da GitHub; el enlace con un repositorio de Skynet no distingue
    -- mayúsculas.
    full_name        text    NOT NULL,
    private          boolean NOT NULL,
    PRIMARY KEY (installation_id, repository_id)
);
CREATE INDEX github_installation_repository_name_idx
    ON github_installation_repository (lower(full_name));

-- Entregas de webhooks (GH-B): la clave es X-GitHub-Delivery, así una entrega repetida no se
-- procesa dos veces. La propia tabla es la cola: un worker reclama las PENDING con
-- FOR UPDATE SKIP LOCKED y un alquiler (locked_until).
CREATE TABLE github_webhook_delivery (
    delivery_id      text PRIMARY KEY,
    event            text        NOT NULL,
    action           text,
    installation_id  bigint,
    repository_name  text,
    status           text        NOT NULL
        CHECK (status IN ('PENDING', 'PROCESSED', 'FAILED', 'IGNORED')),
    attempts         integer     NOT NULL DEFAULT 0,
    run_after        timestamptz NOT NULL,
    locked_until     timestamptz,
    last_error       text,
    payload          jsonb       NOT NULL,
    received_at      timestamptz NOT NULL,
    processed_at     timestamptz
);
CREATE INDEX github_webhook_delivery_pending_idx
    ON github_webhook_delivery (run_after) WHERE status = 'PENDING';
CREATE INDEX github_webhook_delivery_received_idx ON github_webhook_delivery (received_at);
