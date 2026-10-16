-- Pedir cambios (W5-B): la petición que se cierra con CHANGES_REQUESTED guarda la invocación que
-- corrige, la reanudación de la sesión de la fase `revise` con el comentario. Se rellena en la
-- misma actualización que cierra la petición, que el trigger de V19 permite.
ALTER TABLE approval_request
    ADD COLUMN correction_agent_run_id uuid REFERENCES agent_run (id),
    ADD CONSTRAINT approval_request_correction_check
        CHECK ((status = 'CHANGES_REQUESTED') = (correction_agent_run_id IS NOT NULL));
