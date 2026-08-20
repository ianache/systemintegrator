# Design Spec: Segregated Units Domain (Brands, Models, Vehicles) Ingestion via Keycloak-Secured Outbound REST APIs

## 1. Executive Summary & Goals

The goal of this design is to support the independent ingestion, synchronization, and outbound dispatch of entities within the **Units** domain: **`brands`**, **`models`**, and **`vehicles`**.

- **Different Dynamics**:
  - **Brands** and **Models** change infrequently; they are ingested with lower sync frequency (e.g., daily or hourly).
  - **Vehicles** reference existing Brands and Models and are created/updated frequently throughout the day.
- **Pure Integration / Stateless Inbound-to-Outbound Pipeline**:
  - Inbound extractions pull from source systems (SIGO / JDBC) via separate Integration Profiles and publish domain-segregated Kafka events (`integration.brands.events`, `integration.models.events`, `integration.vehicles.events`).
  - Outbound event dispatchers consume these events from Kafka and invoke **Keycloak-secured REST APIs** on CL2 Core microservices (`/api/v1/brands`, `/api/v1/models`, `/api/v1/vehicles`) using **OAuth2 Client Credentials (JWT Bearer tokens)** without persisting locally in local domain tables.

---

## 2. Architecture & Data Flow

```
[External Source: SIGO DB]
       │ (1. Scheduled JDBC Inbound Extractions)
       ▼
[IntegrationSyncOrchestrator]
       │ (2. Topic: integration.<businessDomain>.events)
       ▼
[Kafka Outbox Relay]
  ├── Topic: integration.brands.events
  ├── Topic: integration.models.events
  └── Topic: integration.vehicles.events
       │ (3. Subscribed via Regex Pattern: integration\..*\.events)
       ▼
[KafkaInboxListener & InboxProcessor]
       │ (4. Dispatches to matching OUTBOUND REST Profiles)
       ▼
[OutboundEventDispatcher]
       │ (5. JSLT / Field Transformation)
       ├── Transforms raw record to target REST API JSON schema
       │ (6. Keycloak Token Resolution & Caching)
       ├── OAuth2TokenCacheManager fetches Bearer JWT via client_credentials
       │ (7. Resilience & Rate Limiting)
       └── CircuitBreaker + Redis Rate Limiter execute HTTP POST
       ▼
[CL2 Core REST APIs (Keycloak Secured)]
  ├── POST https://api.cl2.com/api/v1/brands
  ├── POST https://api.cl2.com/api/v1/models
  └── POST https://api.cl2.com/api/v1/vehicles
```

---

## 3. Detailed Component Design

### 3.1 OAuth2 / Keycloak Secret Management
Extend the Secret and Vault infrastructure to first-class support `OAUTH2_CLIENT_CREDENTIALS`.

#### 3.1.1 `ResolvedSecret` & `AuthType`
- `AuthType`: Add/ensure `OAUTH2_CLIENT_CREDENTIALS`.
- `ResolvedSecret` updated with fields:
  ```java
  public record ResolvedSecret(
      String credentialRef,
      AuthType authType,
      String username,
      String password,
      String apiKey,
      String token,
      String tokenUrl,
      String clientId,
      String clientSecret,
      String scope,
      Map<String, String> headers
  ) {
      public static ResolvedSecret oauth2(String credentialRef, String tokenUrl, String clientId, String clientSecret, String scope) {
          return new ResolvedSecret(credentialRef, AuthType.OAUTH2_CLIENT_CREDENTIALS, null, null, null, null, tokenUrl, clientId, clientSecret, scope, Map.of());
      }
  }
  ```

#### 3.1.2 `VaultSecretResolver` & `InMemorySecretResolver`
- When resolving a `credentialRef` (e.g. `secret/cl2/keycloak-credentials`):
  - If keys `tokenUrl` / `token_url`, `clientId` / `client_id`, and `clientSecret` / `client_secret` exist, construct `ResolvedSecret.oauth2(...)`.
  - Provide fallback support in `InMemorySecretResolver` for tests and local development.

### 3.2 Keycloak Token Fetcher & Cache Manager
- Use `OAuth2TokenCacheManager` to fetch JWT access tokens from Keycloak OpenID Connect endpoint (`tokenUrl`) using `grant_type=client_credentials`.
- In-memory cache keyed by `tenantId:clientId:tokenUrl` with automatic proactive expiration (60 seconds before JWT expiration).
- Automatic token refresh on cache expiration or 401 response.

### 3.3 Outbound REST Dispatcher & HTTP Client
- **`OutboundEventDispatcher`**:
  - Matches active `OUTBOUND` or `BIDIRECTIONAL` profiles having `protocol = REST` and `businessDomain` matching the event's domain (`brands`, `models`, `vehicles`, etc.).
  - Executes payload transformation (JSLT or Field Mapping).
  - Resolves target profile credentials (`credentialRef`).
  - Passes `tenantId`, `endpoint`, `ResolvedSecret`, and transformed payload to `HttpOutboundClient`.
- **`HttpOutboundClient`**:
  - When `secret.authType() == AuthType.OAUTH2_CLIENT_CREDENTIALS`:
    - Calls `OAuth2TokenCacheManager.getAccessToken(...)` to retrieve the Bearer JWT.
    - Adds `Authorization: Bearer <JWT>` header to the HTTP POST request.
    - Sends the request to the configured REST endpoint.

---

## 4. Domain Ingestion Profiles Configuration

### 4.1 Inbound Profile: `brands`
- **Domain**: `brands`
- **Direction**: `INBOUND`
- **Topic**: `integration.brands.events`
- **Schedule**: Low frequency (e.g. `0 0 2 * * *` - daily at 2:00 AM)
- **Source**: SIGO DB table `tb_marcas`

### 4.2 Inbound Profile: `models`
- **Domain**: `models`
- **Direction**: `INBOUND`
- **Topic**: `integration.models.events`
- **Schedule**: Low frequency (e.g. `0 0 3 * * *` - daily at 3:00 AM)
- **Source**: SIGO DB table `tb_modelos` (contains `codigo_marca` / `id_marca`)

### 4.3 Inbound Profile: `vehicles`
- **Domain**: `vehicles`
- **Direction**: `INBOUND`
- **Topic**: `integration.vehicles.events`
- **Schedule**: High frequency (e.g. `0 */10 * * * *` - every 10 minutes)
- **Source**: SIGO DB table `tb_vehiculo` (contains references to model/brand)

### 4.4 Outbound REST Profiles
- **Brands Outbound**:
  - `businessDomain`: `brands`, `direction`: `OUTBOUND`, `protocol`: `REST`
  - `endpoint`: `https://api.cl2.com/api/v1/brands`
  - `credentialRef`: `secret/cl2/keycloak-credentials`
- **Models Outbound**:
  - `businessDomain`: `models`, `direction`: `OUTBOUND`, `protocol`: `REST`
  - `endpoint`: `https://api.cl2.com/api/v1/models`
  - `credentialRef`: `secret/cl2/keycloak-credentials`
- **Vehicles Outbound**:
  - `businessDomain`: `vehicles`, `direction`: `OUTBOUND`, `protocol`: `REST`
  - `endpoint`: `https://api.cl2.com/api/v1/vehicles`
  - `credentialRef`: `secret/cl2/keycloak-credentials`

---

## 5. Resilience & Error Handling

1. **Circuit Breaker**: Resilience4j isolates failing endpoints per `tenant:connector`.
2. **Dead Letter Queue (DLQ)**: In case of persistent HTTP 5xx or connection failures, events transition from `integration_inbox` (`PENDING` -> `FAILED` -> `DEAD_LETTER`) without blocking subsequent domain events.
3. **Distributed Rate Limiter**: Configurable per profile to avoid overwhelming target CL2 REST endpoints or Keycloak auth servers during batch syncs.

---

## 6. Verification & Test Plan

1. **Unit Tests**:
   - `OAuth2TokenCacheManagerTest`: Verify token retrieval, TTL caching, proactive expiration, and multi-tenant keying.
   - `VaultSecretResolverTest`: Verify parsing and construction of OAuth2 secret records from Vault responses.
   - `HttpOutboundClientTest`: Verify `Authorization: Bearer <token>` header injection when using OAuth2 secret.
   - `OutboundEventDispatcherTest`: Verify proper dispatching for `brands`, `models`, and `vehicles` domain events.
2. **Integration Tests (WireMock)**:
   - Mock Keycloak OpenID Connect token endpoint issuing JWTs.
   - Mock CL2 Core REST endpoints verifying received headers and payloads.
   - Verify complete flow: Inbound Extraction -> Outbox -> Kafka -> Inbox -> Transformation -> Keycloak JWT -> Outbound HTTP POST.
