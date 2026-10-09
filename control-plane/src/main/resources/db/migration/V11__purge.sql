-- AE-B: eliminar lo archivado. El historial sigue siendo append-only, salvo dentro de una purga:
-- la transacción que elimina algo activa `SET LOCAL skynet.purge = 'on'` y puede borrar sus
-- eventos (deja en su lugar un evento lápida). Modificar un evento sigue sin estar permitido.
CREATE OR REPLACE FUNCTION event_is_append_only() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' AND current_setting('skynet.purge', true) = 'on' THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'event es append-only';
END;
$$ LANGUAGE plpgsql;

-- Búsquedas de la purga que no tenían índice: blobs aún en uso y worktrees aún referenciados.
CREATE INDEX artifact_uri_idx ON artifact (uri);
CREATE INDEX verification_run_workspace_idx ON verification_run (workspace_id);
