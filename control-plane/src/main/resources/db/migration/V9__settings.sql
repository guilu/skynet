-- Ajustes de la web (UI-G): un documento JSON por clave. De momento solo 'appearance', la paleta
-- elegida en Ajustes > Apariencia; sin fila, la web usa sus colores por defecto.
CREATE TABLE app_setting (
    key        text        PRIMARY KEY,
    value      jsonb       NOT NULL,
    updated_at timestamptz NOT NULL
);
