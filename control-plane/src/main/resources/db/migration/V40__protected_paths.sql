-- VA-A: rutas protegidas del repositorio. Globs relativos a su raíz que ningún agente de ninguna
-- ejecución puede cambiar; se suman a las del workflow y las de la fase.
ALTER TABLE repository
    ADD COLUMN protected_paths text[] NOT NULL DEFAULT '{}';
