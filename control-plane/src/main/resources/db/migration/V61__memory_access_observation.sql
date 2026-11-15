-- MV-F: «¿Quién lo vio?» lee los accesos de una entrada; sin índice recorrería la tabla entera.
CREATE INDEX memory_access_observation ON memory_access (observation_id)
    WHERE observation_id IS NOT NULL;
