---
okf_version: "0.2"
title: Informe de trazabilidad C4
status: REQUIRES_REVIEW
human-reviewed: false
---

# Trazabilidad

| Afirmación del diagrama | Evidencia | Procedencia |
|---|---|---|
| El shell hace proxy de `/bff/` y `/auth/` al BFF | [shell.nginx.conf](../../backoffice/docker/shell.nginx.conf) | EXTRACTED |
| El BFF hace OIDC code + PKCE y guarda la sesión en Redis | [auth.controller.ts](../../backoffice/apps/bff/src/auth/auth.controller.ts), [configure-session.ts](../../backoffice/apps/bff/src/session/configure-session.ts) | EXTRACTED |
| El BFF refresca el access token antes de llamar al gateway | `SessionAccessTokenGuard` en [gateway-proxy.controller.ts](../../backoffice/apps/bff/src/gateway-proxy/gateway-proxy.controller.ts) | EXTRACTED |
| El gateway exige `tenant_id` UUID y lo inyecta como `X-Tenant-ID` | [TenantClaimGatewayFilter.java](../../gateway/src/main/java/com/cl2/integration/gateway/security/TenantClaimGatewayFilter.java) | EXTRACTED |
| `integration-app` rechaza peticiones sin `X-Tenant-ID` válido | [TenantFilter.java](../../application/src/main/java/com/cl2/integration/infrastructure/tenant/TenantFilter.java) | EXTRACTED |
| Controller → Service → Port → Adapter en perfiles | [IntegrationProfileService.java](../../application/src/main/java/com/cl2/integration/application/IntegrationProfileService.java) | EXTRACTED |
| La sync escribe en outbox y el relay publica en Kafka | Orchestrator y [OutboxRelayScheduler.java](../../application/src/main/java/com/cl2/integration/integration/outbox/OutboxRelayScheduler.java) | EXTRACTED |
| `IntegrationSyncService` invoca al Orchestrator | No leí el cuerpo de `IntegrationSyncService` | INFERRED |
| El mismo Redis lo usan BFF y `integration-app` | No verificado en la configuración de ambos | INFERRED |
