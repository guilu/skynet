-- MV-D: lo que cada invocación recibió o leyó de la wiki del proyecto queda también en
-- `memory_access`, con la versión de la página: el índice que llevó en su prompt (`PROMPT` o
-- `CONTEXT`) y las páginas que pidió con wiki_index y wiki_read (`GET`). Cada fila es de una
-- observación o de una página, nunca de las dos.
ALTER TABLE memory_access
    ALTER COLUMN observation_id DROP NOT NULL,
    ADD COLUMN wiki_page_id uuid REFERENCES wiki_page (id) ON DELETE CASCADE,
    ADD CONSTRAINT memory_access_target_check
        CHECK ((observation_id IS NULL) <> (wiki_page_id IS NULL));

CREATE INDEX memory_access_wiki_page ON memory_access (wiki_page_id) WHERE wiki_page_id IS NOT NULL;
