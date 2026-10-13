-- PV-D: proveedor y modelo elegidos al lanzar, para las fases de agente sin agente con nombre (como
-- la de adhoc). NULL: Claude Code con el modelo global.
ALTER TABLE workflow_run ADD COLUMN launch_provider text;
ALTER TABLE workflow_run ADD COLUMN launch_model text;
