import { cva, type VariantProps } from 'class-variance-authority'
import type { ButtonHTMLAttributes } from 'react'
import { cn } from '../../lib/cn'

const button = cva('', {
  variants: {
    variant: {
      primary: 'btn',
      secondary: 'btn btn-secondary',
      success: 'btn btn-success',
      danger: 'btn btn-danger',
      'secondary-danger': 'btn btn-secondary-danger',
      link: 'btn-link',
    },
    size: { md: '', sm: 'btn-sm' },
  },
  defaultVariants: { variant: 'primary', size: 'md' },
})

export type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & VariantProps<typeof button>

/**
 * Botón con volumen: color sólido y una base que desaparece al pulsar. `secondary` es blanco con
 * borde, `danger` y `success` llevan el color del estado, y `link` se ve como un enlace.
 */
export function Button({ variant, size, className, type = 'button', ...props }: ButtonProps) {
  return (
    <button
      type={type}
      className={cn(button({ variant, size: variant === 'link' ? undefined : size }), className)}
      {...props}
    />
  )
}
