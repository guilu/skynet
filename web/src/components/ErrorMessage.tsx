export function ErrorMessage({ error }: { error: unknown }) {
  if (!error) return null
  return (
    <p role="alert" className="error">
      {error instanceof Error ? error.message : String(error)}
    </p>
  )
}
