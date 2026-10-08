---
okf_version: "0.2"
title: C4 Documentation Pack - Plataforma de Integración
mode: PRODUCT_MODE
status: REQUIRES_REVIEW
human-reviewed: false
---

# Documentación C4: Plataforma Multitenant de Integración

Modo `PRODUCT_MODE`. Estado `REQUIRES_REVIEW` (ver [documentation-review.md](documentation-review.md)).

| Vista | Documento | Foco |
|---|---|---|
| Context | [context.md](context.md) | Plataforma de Integración |
| Container | [container.md](container.md) | integration-app |
| Component | [component.md](component.md) | IntegrationSyncOrchestrator |
| Code | [code.md](code.md) | IntegrationSyncOrchestrator |
| Secuencias E2E | [sequence-e2e.md](sequence-e2e.md) | S1 login, S2 listar perfiles, S3 sync inbound a outbound |

Los fuentes Mermaid están en [diagrams/](diagrams/). El borde `dark red 700` (`#B71C1C`) marca el foco; los demás elementos usan gris 700.

Fuentes de contexto: [solution_architecture.md](../solution_architecture.md), ADRs [0001](../adrs/0001-microui-architecture-shell-native-federation.md) a [0007](../adrs/0007-bff-nodejs-framework-nestjs.md), [docker-compose.yaml](../../docker-compose.yaml) y el código de `application/`, `gateway/` y `backoffice/`. No usé un DCP ni un DTC: el repositorio no los contiene.
