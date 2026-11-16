import type { ReactNode } from 'react'
import Markdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { safeLink } from '../run/artifacts'
import { Button } from '../ui/Button'

/** El nombre de otra página de la wiki, como lo enlazan sus páginas: `[Lo hecho](hecho)`. */
const SLUG = /^[a-z0-9][a-z0-9-]*$/

/**
 * El markdown de una página de la wiki, sin HTML ni imágenes y con enlaces solo `http`, `https` o
 * `mailto`, como los ficheros generados: lo escriben Skynet, el curador y las personas. Un enlace a
 * otra página (solo su nombre) la abre en el mismo panel.
 */
export function WikiMarkdown({
  text,
  label,
  onPage,
}: {
  text: string
  label: string
  onPage: (slug: string) => void
}) {
  return (
    <article className="markdown" aria-label={label}>
      <Markdown
        remarkPlugins={[remarkGfm]}
        skipHtml
        urlTransform={(url) => (SLUG.test(url) ? url : safeLink(url))}
        components={{
          a: ({ href, children }) => {
            if (href && SLUG.test(href)) {
              return (
                <Button variant="link" onClick={() => onPage(href)}>
                  {children}
                </Button>
              )
            }
            return href ? (
              <a href={href} target="_blank" rel="noopener noreferrer">
                {children}
              </a>
            ) : (
              <span>{children}</span>
            )
          },
          img: ({ alt }): ReactNode => <span className="muted">[imagen: {alt}]</span>,
        }}
      >
        {text}
      </Markdown>
    </article>
  )
}
