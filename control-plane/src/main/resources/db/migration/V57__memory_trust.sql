-- MV-A: confianza de la memoria (docs/memoria.md, «Confianza»). Lo que guarda un agente no llega
-- a los demás agentes hasta que se confirma: al fusionarse la PR de su ejecución, al aprobarla una
-- persona o al editarla o fijarla alguien. Lo que parece una orden para el agente queda en
-- cuarentena y no llega a ningún agente hasta que una persona lo revise.
ALTER TABLE memory_observation
    ADD COLUMN trust text NOT NULL DEFAULT 'CONFIRMED'
        CHECK (trust IN ('CONFIRMED', 'UNCONFIRMED', 'QUARANTINED')),
    -- Por qué tiene esa confianza, en palabras: «Se fusionó la PR de su ejecución», «Editada por
    -- diego», el motivo de la cuarentena…
    ADD COLUMN trust_reason text,
    ADD COLUMN trust_changed_at timestamptz;

-- Lo guardado antes de MV-A se queda como estaba: confirmado. A partir de aquí, cada guardado
-- dice su confianza.
ALTER TABLE memory_observation ALTER COLUMN trust DROP DEFAULT;

-- Confirmar lo de una ejecución cuando se fusiona su PR.
CREATE INDEX memory_observation_unconfirmed ON memory_observation (source_workflow_run_id)
    WHERE trust = 'UNCONFIRMED';
