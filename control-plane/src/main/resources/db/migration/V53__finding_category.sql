-- AP-A: la clase de cada hallazgo, de una lista cerrada que dice el juez, para contar lo que se
-- repite en un repositorio. Los hallazgos de antes quedan en `otro`: no se reclasifican.
ALTER TABLE finding ADD COLUMN category text NOT NULL DEFAULT 'otro'
    CHECK (category IN ('tests', 'caso-limite', 'errores', 'entradas', 'secretos', 'concurrencia',
                        'rendimiento', 'api', 'datos', 'alcance', 'estilo', 'documentacion',
                        'otro'));

CREATE INDEX finding_repository_category ON finding (repository_id, category, created_at);
