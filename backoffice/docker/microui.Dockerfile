FROM node:22-alpine AS build

WORKDIR /workspace
COPY package.json package-lock.json ./
RUN npm install --ignore-scripts --no-audit --no-fund
COPY . .
RUN npx nx build integration-mfe

FROM nginx:1.27-alpine
COPY docker/microui.nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/dist/apps/integration-mfe/browser /usr/share/nginx/html
EXPOSE 80
