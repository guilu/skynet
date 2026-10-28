# Probar LG-A - Logotipos de los proveedores en Uso

En la página **Uso**, la cabecera de cada tarjeta de «Ventanas de uso» llevaba la inicial del CLI (C, X, A). Ahora lleva el logotipo de la empresa que hay detrás:

- **Claude Code:** el logotipo de Anthropic, en su naranja arcilla.
- **Codex:** el logotipo de OpenAI, en gris.
- **Antigravity CLI:** la estrella de Gemini, con su degradado azul, morado y rosa.

Los trazos vienen de Simple Icons (CC0) y van en línea en `web/src/components/usage/ProviderLogo.tsx`: no hay dependencias nuevas. Un proveedor sin logotipo sigue llevando su inicial. La API, los datos y las ejecuciones no cambian.

## 1. Pruebas automáticas

```bash
cd web
npx vitest run src/components/usage   # logotipos y formato de cuotas
npm run lint && npm run typecheck && npm run format:check
cd ..
```

Comprueban:

- **Claude Code, Codex y Antigravity** dibujan un SVG y ya no una letra.
- **El degradado de Gemini** usa su propio id (no choca si hay dos tarjetas).
- **Cualquier otro proveedor** sigue con la inicial de su nombre.

## 2. En la web

Arranca como siempre (`docs/arranque-local.md`) y entra en `http://localhost:5173/usage`. No hace falta lanzar ningún agente: las tarjetas «Sin datos» también llevan el logotipo.

1. **Ventanas de uso:** cada tarjeta muestra a la izquierda de su nombre:
   - Claude Code: la «A» de Anthropic en naranja sobre fondo crema.
   - Codex: el nudo de OpenAI en gris sobre fondo gris claro.
   - Antigravity CLI: la estrella de cuatro puntas de Gemini con degradado sobre fondo azul claro.
2. **Tamaño:** el logotipo queda centrado en su baldosa de 48 px, sin cortarse ni deformarse.
3. **Tema oscuro:** los tres se ven con buen contraste (el de OpenAI pasa a gris claro).
4. **Móvil** (ventana estrecha): las tarjetas se apilan y los logotipos se ven igual.

## 3. Qué no cambia

- **Medidores, estados («Disponible», «Sin datos»), avisos y gráfica** de la página Uso.
- **Iconos del resto de la app** (paquete Ilustrados o ChatGPT de Ajustes › Apariencia).

## 4. Al terminar

```bash
git status --porcelain   # vacío: la guía no deja cambios en el checkout
```
