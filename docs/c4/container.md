---
okf_version: "0.2"
title: C4 Nivel 2 - Container
c4_level: Container
focus_id: integration-app
focus_name: integration-app
focus_kind: container
focus_highlight: dark-red-700-border
status: REQUIRES_REVIEW
human-reviewed: false
---

# C4 Container

Foco: `integration-app`, el backend Spring Boot que contiene la API, la sincronización y el motor de mensajería.

```mermaid
flowchart TB
    user["Administrador<br/>[Persona]"]
    subgraph backoffice["Backoffice"]
        shell["backoffice-shell<br/>[Angular + Native Federation, nginx :4200]<br/>Application Shell, authGuard, proxy /bff y /auth"]
        mfe["backoffice-microui (integration-mfe)<br/>[Angular remote, nginx :4202]<br/>Consola: perfiles, flujos, monitor, credenciales, DLQ"]
        bff["backoffice-bff<br/>[NestJS :4000]<br/>OIDC code+PKCE, sesión, proxy al Gateway"]
    end
    subgraph core["Plataforma de integración"]
        gw["integration-middleware<br/>[Spring Cloud Gateway :8081]<br/>JWT multi-issuer, claim tenant_id a X-Tenant-ID"]
        app["integration-app<br/>[Spring Boot :8080, hexagonal]<br/>API /api/v1, sync, outbox/inbox, outbound"]
    end
    redis[("integration-redis<br/>[Redis 7.4]<br/>Sesiones BFF, rate limiter")]
    mysql[("integration-mysql<br/>[MySQL 8.4]<br/>Perfiles, flujos, outbox, inbox, sync_state, shedlock")]
    kafka[["integration-kafka<br/>[Kafka KRaft]<br/>integration.dominio.events, DLQ"]]
    vault["integration-vault<br/>[Vault KV v2]"]
    kc["Keycloak<br/>[Externo]"]
    extdb["BD externas<br/>[Externo]"]
    extapi["APIs REST externas<br/>[Externo]"]

    user -->|"HTTPS"| shell
    shell -->|"Carga remoteEntry.json"| mfe
    shell -->|"/auth/*, /bff/* (proxy_pass)"| bff
    bff -->|"Sesión (connect-redis)"| redis
    bff -->|"Authorization Code + PKCE"| kc
    bff -->|"HTTP + Bearer JWT"| gw
    gw -->|"JWKS"| kc
    gw -->|"HTTP + X-Tenant-ID, /api/**"| app
    app -->|"JPA/Flyway"| mysql
    app -->|"Produce/consume eventos"| kafka
    app -->|"Secretos"| vault
    app -->|"JDBC"| extdb
    app -->|"HTTP"| extapi

    classDef focus stroke:#B71C1C,stroke-width:4px,fill:#ffffff,color:#212121;
    classDef other stroke:#616161,stroke-width:2px,fill:#ffffff,color:#212121;
    class app focus;
    class user,shell,mfe,bff,gw,redis,mysql,kafka,vault,kc,extdb,extapi other;
```

| Container | Tecnología / puerto | Fuente | Procedencia |
|---|---|---|---|
| backoffice-shell | Angular + Native Federation, nginx, 4200 | [docker-compose.yaml](../../docker-compose.yaml), [shell.nginx.conf](../../backoffice/docker/shell.nginx.conf) | EXTRACTED |
| backoffice-microui | Angular remote `integration-mfe`, nginx, 4202 | [app.routes.ts](../../backoffice/apps/shell/src/app/app.routes.ts) | EXTRACTED |
| backoffice-bff | NestJS, 4000 | [main.ts](../../backoffice/apps/bff/src/main.ts), [ADR 0007](../adrs/0007-bff-nodejs-framework-nestjs.md) | EXTRACTED |
| integration-middleware | Spring Cloud Gateway, 8081 | [gateway](../../gateway) | EXTRACTED |
| integration-app | Spring Boot, 8080 | [application](../../application) | EXTRACTED |
| integration-mysql, integration-kafka, integration-vault | MySQL, Kafka, Vault | [docker-compose.yaml](../../docker-compose.yaml) | EXTRACTED |
| integration-redis | Redis; sesiones del BFF y rate limiter | [configure-session.ts](../../backoffice/apps/bff/src/session/configure-session.ts) | EXTRACTED (uso por el BFF); INFERRED (que sea la misma instancia que usa `integration-app`) |

Fuera de este diagrama quedan `integration-prometheus` e `integration-grafana`, que son observabilidad y también corren en el compose.

El perfil `qa-e2e` activa en el gateway la validación JWT contra `microservicios` y `Apps` ([GatewaySecurityConfig.java](../../gateway/src/main/java/com/cl2/integration/gateway/security/GatewaySecurityConfig.java)).
