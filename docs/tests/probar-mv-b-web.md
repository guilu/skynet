# Probar MV-B (web) - Lanzar igualmente

Segunda PR de MV-B (#307), solo de web. MV-B ya hace que lanzar un trabajo muy parecido a uno entregado responda 409 `DONE_MATCH` (ver [`probar-mv-b.md`](probar-mv-b.md)). Hasta ahora la web solo enseñaba el error. Ahora:

- **El formulario de lanzar** enseña un aviso, «Esto parece ya hecho», con cada trabajo entregado que se parece:
  - su clave y título;
  - el enlace a su PR;
  - el parecido, o «este mismo trabajo» si se relanza lo ya entregado.
- **«Lanzar igualmente»** lo lanza con `confirmDone: true`.
- **«No lanzar»** quita el aviso.
- Mientras se ve el aviso, el botón «Lanzar» queda desactivado.

No cambia la API ni la base de datos.

## 1. Pruebas automáticas

```bash
cd web
npx tsc -b && npx oxlint && npx prettier --check . && npx vitest run src/components/launch
```

`LaunchForm.test.tsx`:
- el primer lanzamiento responde 409 y sale el aviso con «AKA-1 «Exportar las notas a PDF» se entregó en la PR #40», «(parecido 67 %)» y el enlace a la PR;
- «No lanzar» lo quita;
- «Lanzar igualmente» repite el lanzamiento con `confirmDone: true` y abre la ejecución.

## 2. A mano, sobre `demo-repo`

Prepara un trabajo entregado como en [`probar-mv-b.md` §2](probar-mv-b.md#2-skynet-desplegado-sin-github): la PR de una ejecución de «Exportar las notas a PDF», marcada `MERGED` en la base de datos **de pruebas**.

1. Crea «Añadir exportación de notas en PDF» en el mismo proyecto y pulsa **Lanzar**.
   **Esperado:** no se lanza. Sale el aviso «Esto parece ya hecho» con «DEMO-… «Exportar las notas a PDF» se entregó en la PR #40 (parecido …)», y «PR #40» enlaza a la PR.
2. Pulsa **No lanzar**. **Esperado:** el aviso desaparece y «Lanzar» vuelve a estar activo.
3. Pulsa **Lanzar** y luego **Lanzar igualmente**.
   **Esperado:** se abre la ejecución nueva, y en su historial aparece `memory.done_match` con `confirmed: true`.
4. Relanza el propio «Exportar las notas a PDF».
   **Esperado:** el aviso dice «, este mismo trabajo, se entregó en la PR #40», sin porcentaje.
5. Lanza «Arreglar el login con Google». **Esperado:** se lanza sin aviso.

El aviso usa los colores de advertencia del tema; compruébalo también en modo oscuro.
