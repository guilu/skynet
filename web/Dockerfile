# Imagen de la web. Contexto de build: web/.
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# Solo el bundle: el typecheck (tsc -b) corre en CI y necesita fixtures/contracts, que queda fuera
# del contexto de build.
RUN npx vite build

FROM nginx:1.29-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
HEALTHCHECK --interval=10s --timeout=3s --retries=6 CMD wget -qO /dev/null http://127.0.0.1/ || exit 1
