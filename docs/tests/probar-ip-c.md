# Probar IP-C - Asistente «Importar proyecto»

IP-C (#254) es la última PR del hito IP: la web del asistente sobre el catálogo de IP-A y la importación de IP-B. Lo cuenta [`docs/importar-proyecto.md`](../importar-proyecto.md) en «El asistente (IP-C)».

- **Web**: `/projects/import` con 4 pasos (Repositorio, Proyecto, Configuración, Resumen), la tarjeta «Importar desde un repositorio» en Proyectos y el aviso «Proyecto importado desde …» en el proyecto.
- **Sin migraciones ni cambios de API**. Añade el contrato `fixtures/contracts/repository-catalog.json` y la carpeta raíz de las E2E (`SKYNET_REPO_ROOTS=build/e2e/code` en `scripts/e2e-service.sh`).
- **No lanza agentes** salvo que marques «Conocer el repositorio» en el paso 4, y esta guía no lo hace.

Ninguna prueba modifica ficheros versionados de skynet ni de otros repositorios.

## 1. Pruebas automáticas

```bash
cd web
npx vitest run src/ImportProject.test.tsx src/components/project/importWizard.test.ts src/contracts.test.ts
cd .. && ./gradlew :control-plane:test --tests '*ContractIT'
./scripts/e2e.sh import-project.e2e.ts
```

- `ImportProject.test.tsx`: sin catálogo sale el estado vacío con `SKYNET_REPO_ROOTS`; con el catálogo de contrato, akademia (registrado) no se puede elegir y enlaza a AKA, forma llega relleno hasta «Crear proyecto», «Usar esa» pone la descripción de GitHub y la llamada lleva ruta, remoto, rama, validación y rutas protegidas.
- `importWizard.test.ts`: nombres, claves, rutas cortas, remotos, orígenes de cada campo y la petición.
- `contracts.test.ts` / `ContractIT`: el catálogo tiene las mismas claves en el backend y en la web.
- `import-project.e2e.ts`, con el runner de verdad: crea un repositorio en la carpeta raíz, «Volver a buscar» lo trae, el remoto llega sin el token, la validación es `npm test`, «Crear proyecto» lleva al proyecto con el aviso, y el repositorio sale después como «Ya está en …». Pasa axe en los pasos 1 y 3.

## 2. En local

Arranca como en la sección 2 de [`probar-ip-a.md`](probar-ip-a.md), con la carpeta `/tmp/ip-a/code` (si importaste `demo-repo` en IP-B, archiva y elimina ese proyecto o usa `clientes/demo-2`). Entra en la web.

### 2.1 Paso 1: Repositorio

- En **Proyectos** está la tarjeta «Importar desde un repositorio»; «Importar proyecto» abre el asistente. La miga de pan dice «Proyectos › Importar proyecto».
- Salen `demo-2` (con «Cambios sin commitear»), `demo-repo` y `notas` (con «Sin remoto»). Lo registrado sale atenuado con «Ya está en CLAVE».
- Escribe `notas` en el buscador: solo queda `notas`.
- Clona otro repositorio en `/tmp/ip-a/code` y pulsa **Volver a buscar**: el botón dice «Buscando…» y el nuevo aparece en unos segundos.

### 2.2 Pasos 2 a 4 con `demo-repo`

- Paso 2: clave `DEM` (o `DEM2` si ya existe) con «Libre», nombre «Demo-repo» o el del manifiesto, y la descripción con su origen. Pon una clave en minúsculas: sale el aviso de formato y «Siguiente» se desactiva.
- Paso 3: la ruta no se puede cambiar; el remoto es `https://github.com/guilu/demo-repo.git`, sin `usuario:ghp_falso@`.
- Paso 4: el resumen, con la casilla «Conocer el repositorio» **desmarcada**. Déjala así y pulsa **Crear proyecto**.
- Llegas al proyecto con «Proyecto importado desde /tmp/ip-a/code/demo-repo» y su repositorio en Configuración. En Actividad sale lo mismo.
- Vuelve a `/projects/import`: `demo-repo` sale como «Ya está en DEM».

### 2.3 Sin carpetas

Para el runner y arráncalo sin `SKYNET_REPO_ROOTS`, y borra su catálogo (`DELETE FROM runner_repository_catalog`). El asistente muestra a Skyny con «El runner todavía no busca repositorios» y cómo configurarlo.

### 2.4 Tema oscuro y móvil

Cambia al tema oscuro y repite el paso 1: contraste correcto, sin sombras en los paneles. Con una ventana de 390 px, los pasos se apilan, la barra de pasos solo nombra el actual y «Atrás / Siguiente» quedan abajo.

## 3. En omarchy

Con `SKYNET_REPO_ROOTS=$HOME/code` (IP-A), abre el asistente: forma, akademia y tokenmeter salen con sus datos. Importa solo los que quieras tener de verdad en Skynet; la casilla «Conocer el repositorio» lanza una ejecución con coste, así que márcala solo si quieres el mapa.
