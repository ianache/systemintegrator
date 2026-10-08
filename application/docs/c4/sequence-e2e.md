---
okf_version: "0.2"
title: Índice de secuencias E2E por endpoint - integration-app
c4_level: Sequence
status: REQUIRES_REVIEW
human-reviewed: false
---

# Cobertura endpoint → secuencia

No hay especificación OpenAPI/Swagger en el repo (busqué `openapi*.y*ml` y `api.yaml`). Los 36 endpoints se derivaron de los controllers (`INFERRED_FROM_CONTROLLER`).

Cobertura: **36 de 36** mappings de `@Get/@Post/@Put/@DeleteMapping` en `src/main/java/**/*Controller.java` tienen secuencia. Hay 36 `.md` en [sequences/](sequences/) y 36 `.mmd` en [diagrams/sequences/](diagrams/sequences/).

## integration-profiles (IntegrationProfileController)
| Endpoint | Secuencia |
|---|---|
| POST /api/v1/integration-profiles | [post-integration-profiles](sequences/post-integration-profiles.md) |
| GET /api/v1/integration-profiles | [get-integration-profiles](sequences/get-integration-profiles.md) |
| GET /api/v1/integration-profiles/{profileId} | [get-integration-profiles-profileid](sequences/get-integration-profiles-profileid.md) |
| PUT /api/v1/integration-profiles/{profileId} | [put-integration-profiles-profileid](sequences/put-integration-profiles-profileid.md) |
| DELETE /api/v1/integration-profiles/{profileId} | [delete-integration-profiles-profileid](sequences/delete-integration-profiles-profileid.md) |
| POST .../{profileId}/pause | [post-integration-profiles-profileid-pause](sequences/post-integration-profiles-profileid-pause.md) |
| POST .../{profileId}/resume | [post-integration-profiles-profileid-resume](sequences/post-integration-profiles-profileid-resume.md) |
| POST .../{profileId}/sync | [post-integration-profiles-profileid-sync](sequences/post-integration-profiles-profileid-sync.md) |
| POST .../{profileId}/mapping/dry-run | [post-integration-profiles-profileid-mapping-dry-run](sequences/post-integration-profiles-profileid-mapping-dry-run.md) |
| POST .../{profileId}/extraction/dry-run | [post-integration-profiles-profileid-extraction-dry-run](sequences/post-integration-profiles-profileid-extraction-dry-run.md) |

## flows (FlowController)
| Endpoint | Secuencia |
|---|---|
| POST /api/v1/flows | [post-flows](sequences/post-flows.md) |
| GET /api/v1/flows | [get-flows](sequences/get-flows.md) |
| GET /api/v1/flows/metrics/summary | [get-flows-metrics-summary](sequences/get-flows-metrics-summary.md) |
| GET /api/v1/flows/{flowId} | [get-flows-flowid](sequences/get-flows-flowid.md) |
| PUT /api/v1/flows/{flowId} | [put-flows-flowid](sequences/put-flows-flowid.md) |
| DELETE /api/v1/flows/{flowId} | [delete-flows-flowid](sequences/delete-flows-flowid.md) |
| GET .../{flowId}/versions | [get-flows-flowid-versions](sequences/get-flows-flowid-versions.md) |
| POST .../{flowId}/versions/publish | [post-flows-flowid-versions-publish](sequences/post-flows-flowid-versions-publish.md) |
| POST .../{flowId}/versions/{versionNumber}/rollback | [post-flows-flowid-versions-versionnumber-rollback](sequences/post-flows-flowid-versions-versionnumber-rollback.md) |
| POST .../{flowId}/executions | [post-flows-flowid-executions](sequences/post-flows-flowid-executions.md) |
| GET .../{flowId}/executions | [get-flows-flowid-executions](sequences/get-flows-flowid-executions.md) |
| GET .../{flowId}/executions/{executionId} | [get-flows-flowid-executions-executionid](sequences/get-flows-flowid-executions-executionid.md) |

## messages, DLQ, credentials, transformations
| Endpoint | Secuencia |
|---|---|
| GET /api/v1/messages | [get-messages](sequences/get-messages.md) |
| GET /api/v1/messages/{direction}/{id} | [get-messages-direction-id](sequences/get-messages-direction-id.md) |
| POST .../messages/{direction}/{id}/retry | [post-messages-direction-id-retry](sequences/post-messages-direction-id-retry.md) |
| POST .../messages/{direction}/{id}/dlq | [post-messages-direction-id-dlq](sequences/post-messages-direction-id-dlq.md) |
| POST /api/v1/inbox/dlq/replay | [post-inbox-dlq-replay](sequences/post-inbox-dlq-replay.md) |
| GET /api/v1/credentials | [get-credentials](sequences/get-credentials.md) |
| POST /api/v1/transformations/preview | [post-transformations-preview](sequences/post-transformations-preview.md) |

## lookups y vehicles
| Endpoint | Secuencia |
|---|---|
| POST /api/v1/lookups | [post-lookups](sequences/post-lookups.md) |
| POST /api/v1/lookups/batch | [post-lookups-batch](sequences/post-lookups-batch.md) |
| GET /api/v1/lookups | [get-lookups](sequences/get-lookups.md) |
| DELETE /api/v1/lookups/{id} | [delete-lookups-id](sequences/delete-lookups-id.md) |
| POST /api/v1/vehicles | [post-vehicles](sequences/post-vehicles.md) |
| GET /api/v1/vehicles | [get-vehicles](sequences/get-vehicles.md) |
| GET /api/v1/vehicles/{vehicleId} | [get-vehicles-vehicleid](sequences/get-vehicles-vehicleid.md) |
