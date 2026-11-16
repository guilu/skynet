# Probar MV-F - Entradas con confianza

Segunda PR de MV-F (#311), el último paso del hito MV «Memoria viva». Solo cambia la web, en la pestaña **Memoria** del proyecto. La API ya estaba: la confianza es de MV-B y «¿Quién lo vio?», de la primera PR de MV-F (#332).

- **Filtro «Confianza»** en la lista: Confirmadas, Sin confirmar o En cuarentena. Las tarjetas que no están confirmadas llevan una etiqueta: «sin confirmar» o «en cuarentena».
- **En la ficha de una entrada:**
  - si no está confirmada, una nota con su confianza, el motivo y lo que pasa con ella mientras tanto;
  - **Confirmar** (también la saca de cuarentena), solo si no está confirmada;
  - **Poner en cuarentena**, con un motivo opcional y el botón **Apartar**, si no lo está ya;
  - **¿Quién lo vio?**: las invocaciones que la recibieron, buscaron, leyeron o guardaron, con su trabajo, fase y proveedor, cómo la vieron, la versión, cuántas veces y cuándo. Cada una enlaza a la invocación.
- La pestaña Memoria de una invocación explica también el modo `curate` (MV-E).

Ninguna prueba de esta guía modifica ficheros versionados de skynet ni abre PRs.

## 1. Pruebas automáticas

```bash
cd web
npx tsc -b && npx oxlint && npx prettier --check . && npx vitest run src/ProjectMemory.test.tsx
```

`ProjectMemory.test.tsx`:
- una entrada sin confirmar sale con «sin confirmar» y el filtro «En cuarentena» pide `trust=QUARANTINED`;
- una entrada en cuarentena enseña «en cuarentena: Parece una orden para el agente», no ofrece ponerla en cuarentena otra vez, lista quién la vio (enlace a `/agent-runs/a7`, «v2 · 3 veces») y al confirmarla la nota desaparece;
- poner en cuarentena con el motivo «Ya no es verdad» lo manda en el cuerpo y deja la entrada en cuarentena, con **Confirmar**;
- una entrada confirmada no tiene **Confirmar** y, si nadie la vio, dice «Ningún agente la ha visto todavía.».

## 2. Skynet desplegado

Trabaja sobre el proyecto de `demo-repo`, en la pestaña **Memoria**.

### 2.1 Poner en cuarentena y confirmar

1. Guarda como persona una **Decisión** «MV-F probar cuarentena» («Qué»: «Nada importante»). Ábrela.
   **Esperado:** sin nota de confianza y sin **Confirmar**; en «¿Quién lo vio?», «Ningún agente la ha visto todavía.».
2. Pulsa **Poner en cuarentena**, escribe «Prueba de MV-F» y pulsa **Apartar**.
   **Esperado:** la etiqueta «en cuarentena», la nota «en cuarentena: Prueba de MV-F» y el botón **Confirmar**. Ya no está **Poner en cuarentena**.
3. Cierra la ficha y elige **Confianza → En cuarentena**.
   **Esperado:** la entrada sale, con la etiqueta «en cuarentena». Con **Confirmadas**, no sale.
4. Ábrela y pulsa **Confirmar**.
   **Esperado:** la nota y la etiqueta desaparecen, y vuelve **Poner en cuarentena**.

### 2.2 Lo que guardan los agentes

1. Elige **Confianza → Sin confirmar**. Si no sale nada, lanza en `demo-repo` un workflow con una fase `memory: write` (guion de [`probar-mv-a.md`](probar-mv-a.md#2-skynet-desplegado-sin-github)) y vuelve.
   **Esperado:** lo que guardaron los agentes y nadie aceptó, con «sin confirmar».
2. Abre una.
   **Esperado:** la nota «sin confirmar», que explica que no va en el bloque inicial, y **Confirmar**. En «¿Quién lo vio?», al menos la invocación que la guardó, «con mem_save» y v1, con enlace a ella.

### 2.3 ¿Quién lo vio?

1. Con **Tipo → Bitácora**, abre la bitácora de una ejecución que tenga otra después.
   **Esperado:** en «¿Quién lo vio?», los agentes de la ejecución siguiente que la recibieron, «en el bloque del prompt», con trabajo, fase y proveedor. El enlace abre la invocación.

Al acabar, archiva «MV-F probar cuarentena».

## 3. Lo que no cambia

- Editar, fijar, archivar y restaurar una entrada van igual.
- Una entrada archivada no ofrece confirmar ni poner en cuarentena.
- La API y la base de datos no cambian.
