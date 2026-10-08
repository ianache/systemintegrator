---
okf_version: "0.2"
title: C4 Context - integration-app
c4_level: Context
focus_id: integration-app
focus_kind: system
focus_highlight: dark-red-700-border
status: REQUIRES_REVIEW
human-reviewed: false
---

# Context

```mermaid
flowchart TB
    gw["integration-middleware<br/>[Gateway, único cliente directo]"]
    app["integration-app<br/>[Sistema foco: API de integración]"]
    vault["HashiCorp Vault<br/>[Externo]"]
    extdb["BD externas<br/>[Externo]"]
    extapi["APIs REST externas<br/>[Externo]"]
    gw -->|"HTTP /api/** + X-Tenant-ID"| app
    app -->|"Secretos"| vault
    app -->|"JDBC"| extdb
    app -->|"HTTP"| extapi

    classDef focus stroke:#B71C1C,stroke-width:4px,fill:#ffffff,color:#212121;
    classDef other stroke:#616161,stroke-width:2px,fill:#ffffff,color:#212121;
    class app focus;
    class gw,vault,extdb,extapi other;
```

El gateway es el único cliente que ve el servicio; autentica el JWT y entrega `X-Tenant-ID` ([TenantClaimGatewayFilter.java](../../../gateway/src/main/java/com/cl2/integration/gateway/security/TenantClaimGatewayFilter.java)). `integration-app` no aplica Spring Security: no hay `SecurityFilterChain`, según el agente que lo revisó. Verifícalo antes de exponerlo sin gateway.
