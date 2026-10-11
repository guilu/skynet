-- W3-C: salida estructurada. Un agente con `output` en el YAML recibe su JSON Schema
-- (--json-schema) y devuelve un JSON que leen las condiciones de las fases siguientes.
ALTER TABLE agent_run
    ADD COLUMN output_schema     text,
    ADD COLUMN structured_output text;
