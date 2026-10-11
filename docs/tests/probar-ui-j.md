# Probar UI-J en local

UI-J rehace el Dashboard según la plantilla: cabecera con el periodo y «Lanzar agente», los avisos sin panel (solo si hay), las ocho tarjetas de métricas, el gráfico con barras redondeadas en su propio panel, las ejecuciones activas y los runners en tarjetas. Solo cambia la web; no hay migraciones ni dependencias nuevas.

## 1. Arrancar

```bash
docker compose -f deploy/docker-compose.yml --profile app up -d --build --wait
```

En desarrollo, `cd web && npm ci && npm run dev` con el control plane levantado como siempre. Abre `/`.

## 2. Qué mirar

1. **Cabecera.**
   - El título saluda con tu usuario: «Buenos días» (6-14 h), «Buenas tardes» (14-21 h) o «Buenas noches».
   - Debajo, el resumen: «N agentes trabajando y M cosas que necesitan tu atención», o «N agentes trabajando. Todo en orden.» si no hay avisos. Los agentes son los activos de los runners en línea.
   - A la derecha, el selector 24 horas · 7 días · 30 días y el botón «Lanzar agente», que lleva a Proyectos (un workflow se lanza desde la página de un trabajo).
2. **Necesita tu atención.**
   - Sin avisos no aparece nada: ni sección ni franja verde.
   - Con avisos, el subtítulo «Necesita tu atención (N)» y debajo una tarjeta por aviso, sin panel alrededor: fallidas en las últimas 24 h (✕ roja, enlaza a la ejecución), agentes sin respuesta (pausa ámbar, enlaza al agente) y runners sin latido (enchufe rojo, enlaza a Runners). Cada una lleva su pastilla de estado a la derecha.
   - Para provocar uno: para un runner y espera a que deje de enviar latidos.
3. **Ocho tarjetas**, fuera de panel, en 4×2 (2 columnas en tableta, 1 en el móvil):
   - Activas, Completadas, Fallidas, Coste; Ejecuciones, Canceladas, Duración mediana, Tokens.
   - Cada una con su línea secundaria: % de las terminadas en completadas, fallidas y canceladas; coste por ejecución; el periodo en Ejecuciones; Tokens con cifras cortas («86,1 k / 14,8 k»).
   - Las cifras de ejecuciones enlazan a la lista con el mismo filtro y periodo.
   - Cambia el periodo en la cabecera: cambian las tarjetas y el gráfico, y la URL lleva `?period=`.
4. **Gráfico.** Un panel «Ejecuciones por día» (o «por hora» con 24 horas), a todo el ancho. Cada segmento de la barra (completadas, fallidas, canceladas, activas) es una pastilla redondeada con un hueco entre segmentos. El tooltip y «Ver como tabla» siguen ahí.
5. **Ejecuciones activas.** La tabla de siempre, solo si hay alguna.
6. **Runners.** Cabecera «Runners» con «Ver runners», y una tarjeta por runner con el estilo de las de arriba: nombre, pastilla En línea / Desconectado y, debajo, la versión de Claude Code y los agentes activos, o el último latido si está desconectado. Sin runners, «Aún no hay runners registrados.».
7. **Móvil y oscuro.** En el móvil el selector y el botón bajan bajo el saludo y la pastilla de cada aviso va bajo su texto. En oscuro todo se lee bien.

## 3. Comprobaciones automáticas

```bash
cd web && npm run typecheck && npm run lint && npm run format:check && npm test && npm run build
```

Las E2E (`scripts/e2e.sh`) esperan ahora el saludo como título del Dashboard.
