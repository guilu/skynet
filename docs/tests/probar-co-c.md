# Probar CO-C - Companion en las páginas y fuera de la pestaña

CO-C ([#162](https://github.com/guilu/skynet/issues/162), cierra [#140](https://github.com/guilu/skynet/issues/140)) lleva la mascota a las páginas y fuera de la pestaña.

Qué trae (solo web):
- **Mascota que asoma** con bocadillo en la cabecera del Dashboard, de cada proyecto y de Uso.
- **Tarjeta de saludo** en el Dashboard con atajos.
- **Banda «Qué pasa ahora»** en cada ejecución.
- **Caras pequeñas y quietas** en la tabla de ejecuciones y en el grafo.
- **Fuera de la pestaña:** el título cuenta los avisos sin leer, el favicon pone la cara con Te necesita o Error y, opcionalmente, avisos del navegador.
- **Documentación:** [`docs/companion.md`](../companion.md), sección «En las páginas y fuera de la pestaña (CO-C)», y [`docs/ui.md`](../ui.md).

No hay cambios en el servidor, ni migraciones, ni dependencias nuevas.

> **Para despliegues automáticos:** el paso 1 no toca nada fuera del repositorio. Los pasos 2 a 6 usan la web del control plane local. Las ejecuciones reales van sobre **demo-repo (proyecto DEMO), nunca sobre skynet**.

## 1. Tests

```bash
cd web && npm run lint && npm run format:check && npm run typecheck && npx vitest run
./scripts/e2e.sh e2e/companion.e2e.ts
```

- **`peek.test.ts`:** los bocadillos, el saludo, la cara de un grupo de ejecuciones y la banda (quién usa qué herramienta, la fase que espera decisión, el fallo, el final y el coste).
- **`tab.test.ts`:** el «(n)» del título, la cara como favicon y los avisos del navegador (solo con la pestaña oculta, con permiso y para lo que importa; al pulsarlo abre su enlace).
- **E2E `companion.e2e.ts`:** además de lo de CO-B, el título con «(n)» tras el aviso, el saludo del Dashboard y la banda «Ejecución completada».

## 2. Dashboard

1. Sin nada en marcha: a la derecha del saludo asoma la mascota con «Todo al día» y, debajo, la tarjeta «Hola, soy Skynet. No hay nada en marcha: ¿lanzamos algo?» con «Lanzar un workflow» y «Ver la última ejecución».
2. Lanza un workflow sobre **demo-repo** (proyecto DEMO):
   - el bocadillo pasa a «¡Todo en marcha! 🚀»;
   - la tarjeta dice «Hay 1 ejecución en marcha» y «Ver la última ejecución» abre esa ejecución.
3. Si llega a una puerta de aprobación: la mascota saluda, el bocadillo dice «1 aprobación te espera» y la tarjeta muestra «Revisar la aprobación», que la abre.
4. **Móvil** (390 px): la mascota y su bocadillo van bajo el saludo; la tarjeta oculta su mascota y los atajos se apilan.

## 3. Proyecto y Uso

1. **Proyecto DEMO:** junto al título, «Nada en marcha» o «1 ejecución en marcha», con la cara de esa ejecución. Otro proyecto sin nada en marcha sigue en «Nada en marcha».
2. **Uso:** con datos de cuota, «Hay cuota de sobra»; si alguna ventana está casi agotada o por encima de su ritmo, En pausa y «Vamos justos de cuota». Sin datos no asoma (queda el estado vacío de CO-A).

## 4. Ejecución, tabla y grafo

1. Abre la ejecución en marcha. Bajo «Progreso» está la banda «Qué pasa ahora»:
   - «Claude Code está usando Bash» (o la herramienta del momento), «… está pensando» o «La fase «…» espera tu decisión»;
   - debajo, «Fase 1 de 3 · 2 min · 0,04 US$».
2. Al terminar: «Ejecución completada» con la mascota quieta. En una que falla (fase `command: exit 1`), «Ha fallado la fase «…»» en rojo.
3. **Ejecuciones:** la columna Estado lleva la cara pequeña junto a la pastilla; no se anima.
4. **Grafo** del progreso (ejecución con varias fases, vista Grafo): cada fase y cada agente lleva su cara en lugar del icono de estado.

## 5. Fuera de la pestaña

1. **Título:** con un aviso sin leer, la pestaña dice «(1) Skynet»; al abrir y cerrar la campana vuelve a «Skynet».
2. **Favicon:** con una aprobación pendiente o tras un fallo, el icono de la pestaña es la cara de la mascota (azul que saluda o roja); al resolverlo vuelve el logo.
3. **Avisos del navegador:**
   1. Ajustes › Companion › «Avisos del navegador con la pestaña oculta». El navegador pide permiso; acéptalo.
   2. Lanza un workflow y cambia a otra pestaña. Al terminar sale «Skynet · Workflow completado» con la clave, sin prompts ni salidas.
   3. Al pulsarlo vuelve a Skynet y abre la ejecución.
   4. Con la pestaña de Skynet visible no sale (solo la tarjeta). Con «Silenciar 1 h» tampoco.
   5. Si se deniega el permiso, la opción explica cómo activarlo en el navegador.

## 6. Tema oscuro y acentos

Con tema oscuro y otro acento, el bocadillo, la tarjeta de saludo y la banda mantienen el contraste, y la mascota sigue al acento. El favicon de la cara usa colores fijos.
