-- AU-A: el nivel de riesgo del cambio, calculado del diff (0 documentación, 1 código, 2 migraciones,
-- CI, dependencias, despliegue, seguridad o secretos). Solo mide: todavía no cambia nada.

-- Lo que suma el repositorio a las reglas de siempre: rutas y patrones que suben a nivel 2.
ALTER TABLE repository
    ADD COLUMN risk_paths    text[] NOT NULL DEFAULT '{}',
    ADD COLUMN risk_patterns text[] NOT NULL DEFAULT '{}';

-- El nivel de cada fase (el máximo de sus agentes) y de la ejecución (el máximo de sus fases), con
-- el resultado que lo fijó: motivos, ficheros y líneas.
ALTER TABLE stage_run
    ADD COLUMN risk        smallint CHECK (risk BETWEEN 0 AND 2),
    ADD COLUMN risk_detail jsonb;

ALTER TABLE workflow_run
    ADD COLUMN risk        smallint CHECK (risk BETWEEN 0 AND 2),
    ADD COLUMN risk_detail jsonb;
