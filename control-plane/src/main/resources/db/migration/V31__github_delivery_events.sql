-- GH-B: cuántos eventos github.* publicó cada entrega (uno por repositorio de Skynet enlazado).
ALTER TABLE github_webhook_delivery ADD COLUMN published_events integer NOT NULL DEFAULT 0;
