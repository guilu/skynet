-- «Reintentar fase» (W6-C): quién pidió el intento. Null si lo programó el motor por su `retry`.
-- Un intento pedido por una persona no cuenta para `maxAttempts`: los reintentos automáticos
-- vuelven a contar desde él.
ALTER TABLE stage_run ADD COLUMN retried_by text;
