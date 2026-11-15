-- MV-C: las páginas de la wiki del proyecto (docs/memoria.md, «La wiki»). Skynet genera unas sin
-- modelo a partir de lo que pasó (lo entregado, los errores corregidos, las decisiones); más
-- adelante, el curador (MV-E) escribirá otras. Cada versión queda en `wiki_page_revision`.
CREATE TABLE wiki_page (
    id            uuid PRIMARY KEY,
    project_id    uuid        NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    -- El repositorio de la página, o NULL si es de todo el proyecto.
    repository_id uuid REFERENCES repository (id) ON DELETE CASCADE,
    slug          text        NOT NULL CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    title         text        NOT NULL,
    body          text        NOT NULL,
    -- De dónde sale: [{kind, id, label, url}], p. ej. la PR, el hallazgo o la observación.
    sources       jsonb       NOT NULL,
    origin        text        NOT NULL CHECK (origin IN ('GENERATED', 'CURATOR', 'HUMAN')),
    version       integer     NOT NULL,
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    updated_by    text        NOT NULL,
    UNIQUE NULLS NOT DISTINCT (project_id, repository_id, slug)
);

CREATE TABLE wiki_page_revision (
    id         uuid PRIMARY KEY,
    page_id    uuid        NOT NULL REFERENCES wiki_page (id) ON DELETE CASCADE,
    version    integer     NOT NULL,
    title      text        NOT NULL,
    body       text        NOT NULL,
    sources    jsonb       NOT NULL,
    origin     text        NOT NULL,
    created_at timestamptz NOT NULL,
    created_by text        NOT NULL,
    UNIQUE (page_id, version)
);
