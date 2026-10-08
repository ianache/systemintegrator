---
okf_version: "0.2"
c4_level: Sequence
endpoint: "GET /api/v1/credentials"
operation: "CredentialCatalogController.list"
status: REQUIRES_REVIEW
human-reviewed: false
---

# GET /api/v1/credentials

Catalogo de credenciales por tenant. Origen: INFERRED_FROM_CONTROLLER (no existe OpenAPI). Participante foco marcado con ★.

```mermaid
sequenceDiagram
    autonumber
    actor Client as Cliente (UI/BFF)
    participant TF as TenantFilter
    participant C as ★ CredentialCatalogController
    participant S as CredentialCatalogService
    participant PR as IntegrationProfileRepository
    participant DB as MySQL
    participant SR as SecretResolver
    participant V as Vault (HTTP)
    participant EH as ApiExceptionHandler

    Client->>TF: GET /api/v1/credentials + X-Tenant-ID
    alt Header invalido
        TF-->>Client: 400 ProblemDetail (TENANT_HEADER_*)
    else Header valido
        TF->>C: TenantContext.set(tenantId)
        C->>C: requireTenantId()
        C->>S: list(tenantId)
        S->>PR: findAll(tenantId, false)
        PR->>DB: SELECT integration_profile
        DB-->>PR: perfiles
        S->>S: agrupar perfiles por credentialRef (usedBy = dominio · fuente)
        loop cada credentialRef distinto
            S->>SR: resolve(ref, tenantId)
            alt Secreto resuelto
                SR-->>S: ResolvedSecret (authType) => estado VIGENTE
            else Excepcion (p.ej. SecretNotFoundException)
                SR-->>S: error (log.debug) => estado SIN_VERIFICAR, type=null
            end
            opt vaultProperties.enabled
                S->>V: GET /v1/secret/metadata/{path} (X-Vault-Token)
                alt OK
                    V-->>S: data.updated_time => rotatedAt
                else Error
                    V-->>S: fallo => rotatedAt=null
                end
            end
        end
        S-->>C: List CredentialSummary (ref, type, usedBy, rotatedAt, state)
        C-->>Client: 200 JSON
    end
    opt Error inesperado
        S->>EH: Exception
        EH-->>Client: 500 INTERNAL_ERROR
    end
```

## Archivos fuente

- [CredentialCatalogController](../../../src/main/java/com/cl2/integration/adapter/in/web/CredentialCatalogController.java)
- [CredentialCatalogService](../../../src/main/java/com/cl2/integration/integration/credential/CredentialCatalogService.java)
- [IntegrationProfilePersistenceAdapter](../../../src/main/java/com/cl2/integration/adapter/out/persistence/IntegrationProfilePersistenceAdapter.java)
- [SecretResolver](../../../src/main/java/com/cl2/integration/integration/security/SecretResolver.java)
- [VaultSecretResolver](../../../src/main/java/com/cl2/integration/integration/security/VaultSecretResolver.java)
- [InMemorySecretResolver](../../../src/main/java/com/cl2/integration/integration/security/InMemorySecretResolver.java)
- [VaultProperties](../../../src/main/java/com/cl2/integration/integration/security/VaultProperties.java)
- [TenantFilter](../../../src/main/java/com/cl2/integration/infrastructure/tenant/TenantFilter.java)
- [TenantContext](../../../src/main/java/com/cl2/integration/infrastructure/tenant/TenantContext.java)
- [ApiExceptionHandler](../../../src/main/java/com/cl2/integration/adapter/in/web/ApiExceptionHandler.java)

## Procedencia

- Contrato del endpoint: INFERRED_FROM_CONTROLLER.
- EXTRACTED: service y llamada REST a Vault metadata. INFERRED: la implementacion de SecretResolver activa (Vault o InMemory) y la tabla MySQL de perfiles; no se verifico la condicion de activacion.
- Todas las rutas (excepto /actuator) exigen cabecera X-Tenant-ID (TenantFilter).
