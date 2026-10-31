# Imagen de la web. Contexto de build: web/.
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# Solo el bundle: el typecheck (tsc -b) corre en CI y necesita fixtures/contracts, que queda fuera
# del contexto de build.
RUN npx vite build

# La variante sin privilegios de la imagen oficial: nginx corre como el usuario nginx (uid 101),
# no como root, y escucha en el 8080.
FROM nginxinc/nginx-unprivileged:1.29-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080 8082
HEALTHCHECK --interval=10s --timeout=3s --retries=6 CMD wget -qO /dev/null http://127.0.0.1:8080/ || exit 1
