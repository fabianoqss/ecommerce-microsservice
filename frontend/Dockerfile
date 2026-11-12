# syntax=docker/dockerfile:1
FROM node:22-alpine@sha256:0e1754758c2df4cfa3e68d59e2e1b7374bb7fd9ea6328eaaacf15cc934af8202 AS build
WORKDIR /app
RUN npm install --global npm@11.19.1
COPY package.json package-lock.json ./
RUN npm ci
COPY angular.json tsconfig.json tsconfig.app.json .postcssrc.json ./
COPY public ./public
COPY src ./src
RUN --mount=type=secret,id=primeui_license \
    PRIMEUI_LICENSE="$(cat /run/secrets/primeui_license 2>/dev/null || true)" && \
    npx ng build --configuration production --define "PRIMEUI_LICENSE='${PRIMEUI_LICENSE}'"

FROM nginxinc/nginx-unprivileged:stable-alpine@sha256:f5116ed4606f3eb015dd4a8e28cb9b351476dc0ad4bc6e9f93b19cec130cdf03
COPY nginx/default.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist/ecommerce-web/browser/ /usr/share/nginx/html/
USER 101:101
EXPOSE 8080
