FROM node:22-alpine AS build

WORKDIR /workspace
COPY package.json package-lock.json ./
RUN npm install --ignore-scripts --no-audit --no-fund
COPY . .
RUN npx nx build shell && npx nx build bff

FROM node:22-alpine

WORKDIR /app
ENV NODE_ENV=production
COPY --from=build /workspace/dist/apps/bff ./
RUN npm install --omit=dev --ignore-scripts --no-audit --no-fund
EXPOSE 4000
CMD ["node", "main.js"]
