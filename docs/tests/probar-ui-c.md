# Probar UI-C en local

UI-C es la tercera PR del rediseño de la interfaz: rehace las listas (Ejecuciones, Proyectos, Runners y Actividad) y el dashboard. La vista de una ejecución y los grafos llegan en UI-D y UI-E.

## 1. Arrancar

```bash
docker compose -f deploy/docker-compose.yml --profile app up -d --build --wait
```

O en desarrollo: control plane y runner como siempre, y `cd web && npm ci && npm run dev`. Hay una dependencia nueva (recharts, para el gráfico) y el control plane cambia: hay que reconstruirlo, porque el API de ejecuciones acepta ahora `q`.

Para que haya algo que ver, lanza dos o tres ejecuciones y deja que alguna falle.

## 2. Qué mirar

1. **Dashboard, excepciones.** Si no pasa nada raro, sale una franja verde «Nada requiere atención ahora mismo.». Si hay agentes sin actividad o runners sin latido, salen como tarjetas ámbar con icono, una al lado de la otra. «Ejecuciones activas» y «Fallidas en las últimas 24 horas» llevan el enlace «Ver todas…» a la derecha del título, y llevan a la lista ya filtrada.
2. **Dashboard, KPIs.** Cuatro tarjetas grandes de color (Activas, Fallidas, Coste, Duración mediana) y cuatro pequeñas (Ejecuciones, Completadas, Canceladas, Tokens). Las cifras que enlazaban antes siguen enlazando a la lista filtrada. El selector 24 horas / 7 días / 30 días cambia todas las cifras.
3. **Dashboard, gráfico.** Barras apiladas por hora o por día: verde completadas, rojo fallidas, gris canceladas, morado activas. Al pasar el ratón sale una pista con la fecha, el desglose y el coste. «Ver como tabla» sigue mostrando los mismos datos en una tabla.
4. **Ejecuciones.** Arriba hay una búsqueda por clave o título del trabajo (`TKM-1` o parte del título), chips de estado que se pueden combinar (En cola, En curso, Completadas, Fallidas, Canceladas), el selector de proyecto y «Columnas». Todo queda en la URL: copia la dirección, ábrela en otra pestaña y verás los mismos filtros. Con un filtro sin resultados sale «Ninguna ejecución con estos filtros.» y el botón «Quitar los filtros».
5. **Columnas.** «Columnas» abre un menú para ocultar o mostrar columnas (por ejemplo Coste). Recarga: la elección se mantiene en este navegador.
6. **Filtro de fecha.** Desde las cifras del dashboard se llega a la lista con «Creadas desde …» como chip; la ✕ del chip lo quita.
7. **Proyectos.** Lista a la izquierda con búsqueda y el formulario «Nuevo proyecto» a la derecha (debajo, en una ventana estrecha). Crear un proyecto funciona como antes.
8. **Runners.** Chips «En línea» y «Sin latido» para filtrar, y la carga como «usados / capacidad» con una barrita. «Revocar token…» sigue en cada fila.
9. **Actividad.** El estado de la conexión en directo va al lado del título (Conectando, En directo o Reconectando), y los filtros de tipo, severidad y origen quedan en la URL.
10. **Móvil.** Con la ventana estrecha, los KPIs pasan a dos columnas con el icono encima, y las tablas se desplazan en horizontal.

## 3. Comprobaciones automáticas

```bash
cd web && npm run typecheck && npm run lint && npm run format:check && npm test && npm run build
./gradlew :control-plane:test --tests '*ReadProjectionsIT*'
```

El gráfico va en su propio fichero (`RunsChart-*.js`, unos 104 kB comprimido) y solo se descarga al abrir el dashboard. La E2E (`scripts/e2e.sh`) pasa axe en todas las páginas.
