-- IP-A: catálogo de repositorios. Cada runner manda los repositorios git que encuentra en sus
-- carpetas raíz (SKYNET_REPO_ROOTS); es una foto completa, así que se guarda una fila por runner y
-- cada envío sustituye a la anterior. El asistente «Importar proyecto» parte de aquí.
CREATE TABLE runner_repository_catalog (
    runner_id     uuid PRIMARY KEY REFERENCES runner (id) ON DELETE CASCADE,
    scanned_at    timestamptz NOT NULL,
    received_at   timestamptz NOT NULL,
    roots         jsonb       NOT NULL,
    repositories  jsonb       NOT NULL,
    truncated     boolean     NOT NULL
);
