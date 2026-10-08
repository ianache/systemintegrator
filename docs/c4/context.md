---
okf_version: "0.2"
title: C4 Nivel 1 - Context
c4_level: Context
focus_id: integration-platform
focus_name: Plataforma Multitenant de Integración
focus_kind: system
focus_highlight: dark-red-700-border
status: REQUIRES_REVIEW
human-reviewed: false
---

# C4 Context

El sistema foco (borde rojo) integra sistemas origen y destino mediante perfiles configurables. Un administrador los gestiona desde la consola web.

```mermaid
flowchart TB
    admin["Administrador de integración<br/>[Persona]<br/>Configura perfiles, flujos y monitorea mensajes"]
    platform["Plataforma Multitenant de Integración<br/>[Sistema foco]<br/>Integraciones inbound/outbound JDBC y REST"]
    kc["Keycloak<br/>[Sistema externo]<br/>Realms Apps y microservicios (OIDC/JWKS)"]
    vault["HashiCorp Vault<br/>[Sistema externo]<br/>Credenciales de integración (KV v2)"]
    extdb["Bases de datos externas<br/>[Sistema externo]<br/>MySQL, SAP HANA, Oracle, PostgreSQL, SQL Server"]
    extapi["APIs REST externas<br/>[Sistema externo]<br/>Destinos outbound"]

    admin -->|"Usa la consola web (HTTPS/OIDC)"| platform
    platform -->|"Autentica usuarios y valida JWT"| kc
    platform -->|"Resuelve secretos por credentialRef"| vault
    platform -->|"Extrae filas (JDBC, solo SELECT)"| extdb
    platform -->|"Entrega eventos transformados (HTTP)"| extapi

    classDef focus stroke:#B71C1C,stroke-width:4px,fill:#ffffff,color:#212121;
    classDef other stroke:#616161,stroke-width:2px,fill:#ffffff,color:#212121;
    class platform focus;
    class admin,kc,vault,extdb,extapi other;
```

| Elemento | Tipo | Fuente | Procedencia |
|---|---|---|---|
| Administrador de integración | Persona | [README del backoffice](../../backoffice/README.md), rutas del [shell](../../backoffice/apps/shell/src/app/app.routes.ts) | EXTRACTED |
| Keycloak (realms `Apps`, `microservicios`) | Externo | [ADR 0003](../adrs/0003-gateway-multi-issuer-trust-realm-apps.md), [application.yml del gateway](../../gateway/src/main/resources/application.yml) | EXTRACTED |
| HashiCorp Vault | Externo | [docker-compose.yaml](../../docker-compose.yaml), `VaultSecretResolver` | EXTRACTED |
| Bases de datos externas | Externo | [solution_architecture.md](../solution_architecture.md), `GenericJdbcAdapter` | EXTRACTED |
| APIs REST externas | Externo | `HttpOutboundClient`, `OutboundEventDispatcher` | EXTRACTED |
