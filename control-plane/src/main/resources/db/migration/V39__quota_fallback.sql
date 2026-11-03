-- CQ-B: proveedor de reserva. Un agente que sustituye a otro proveedor sin cuota guarda a cuál,
-- su ventana bloqueada y cuándo se renueva.
ALTER TABLE agent_run
    ADD COLUMN fallback_from text,
    ADD COLUMN fallback_window text,
    ADD COLUMN fallback_until timestamptz;
