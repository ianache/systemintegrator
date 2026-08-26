# Generic REST Inbound Adapter Design

**Status:** Proposed
**Date:** 2026-08-25
**Scope:** REST polling for `IntegrationProfile` synchronization

## Objective

Enable an `IntegrationProfile` with `protocol=REST` and `syncDirection=INBOUND` or `BIDIRECTIONAL` to poll an external JSON API, extract records from a configurable JSONPath, transform each record through the existing `TransformationService`, and persist canonical events through the existing transactional outbox flow.

## Existing boundaries

- `IntegrationSyncOrchestrator` already owns the synchronization transaction, watermark state, transformation, duplicate detection, and outbox persistence.
- `GenericJdbcAdapter` remains responsible only for JDBC extraction.
- `HttpOutboundClient` remains responsible only for event-driven outbound HTTP dispatch.
- `SecretResolver` and `ResolvedSecret` are the source of runtime credentials.
- `ExtractionConfig` is the profile configuration contract for both JDBC and REST extraction.

## Proposed architecture

`IntegrationSyncOrchestrator` selects the extraction strategy from `IntegrationProfile.configuration().protocol()`:

- `JDBC`: existing `GenericJdbcAdapter` path.
- `REST`: new `GenericRestAdapter` path.
- Any other protocol: fail the synchronization with an explicit unsupported-protocol error.

The new adapter exposes one synchronous operation:

```java
List<Map<String, Object>> extract(
    IntegrationProfile profile,
    ExtractionConfig config,
    ResolvedSecret secret,
    Instant watermarkTimestamp
)
```

The adapter builds one request per synchronization run. It combines the profile endpoint with `config.path`, adds configured query parameters and headers, substitutes the configured watermark token with the current watermark, applies authentication, executes the request using Spring `RestClient`, parses the JSON response, and evaluates `config.responseJsonPath` using the existing JSONPath dependency.

## Request construction

- Allowed methods: `GET`, `POST`, `PUT`, and `PATCH` only when explicitly configured; `DELETE`, `TRACE`, and `CONNECT` are rejected for inbound extraction.
- The endpoint must be an absolute `http` or `https` URI.
- `path` is resolved relative to the endpoint when present; an absolute path is normalized without duplicating slashes.
- `queryParams` and `headers` are copied into the request without mutating the profile configuration.
- Every value equal to `:lastSyncWithBuffer` is replaced with the watermark formatted according to `watermarkFormat`.
- `ISO_8601` uses UTC `Instant` text. Unsupported formats fail validation rather than silently changing semantics.
- Configured credentials and sensitive headers are applied by authentication type:
  - `BASIC`: HTTP Basic authentication.
  - `BEARER`: static Bearer token.
  - `API_KEY`: `X-API-Key` plus configured custom headers.
  - `OAUTH2_CLIENT_CREDENTIALS`: token from `OAuth2TokenCacheManager`, then Bearer authentication plus configured headers.
- Profile headers cannot override the generated `Authorization` header.

## Response extraction

- Accept only successful 2xx responses with a JSON body.
- Parse the body as a JSON tree before applying JSONPath.
- `responseJsonPath` must resolve to an array for multi-record ingestion. A single object is accepted as a one-element result only when the path resolves to an object.
- Scalar results, missing paths, malformed JSON, and invalid JSONPath expressions fail the run with an explicit extraction error.
- Each extracted object is converted to a `Map<String, Object>` for the existing transformation and outbox pipeline.
- The existing `keyProperty`/`keyColumn` lookup remains responsible for deriving the aggregate key. REST profiles must provide `keyProperty` or the synchronization fails before writing events.
- The REST adapter does not advance the watermark. Watermark advancement remains atomic with outbox writes in the orchestrator.

## Resilience and observability

- The orchestrator invokes REST extraction through `ResilienceExecutor` using the profile tenant and connector.
- The adapter uses a bounded connect/read timeout from application configuration; it must not wait indefinitely.
- HTTP status, URL, and response details are logged without credentials or full sensitive payloads.
- Existing synchronization success/failure and outbox metrics are reused. No high-cardinality URL or token labels are introduced.

## Error handling

The following errors are terminal for the current synchronization run and are recorded through the existing failure path:

- missing or invalid REST endpoint;
- missing `extractionConfig`;
- unsupported method or watermark format;
- missing REST authentication secret when `credentialRef` is required;
- token acquisition failure;
- non-2xx response;
- malformed JSON or invalid JSONPath;
- response shape incompatible with record extraction;
- missing record key property.

No records are written to the outbox when request construction or response extraction fails. The existing transaction and sync-state failure handling preserve the last successful watermark.

## Testing strategy

Tests must be written before production implementation and must demonstrate a failing expectation first.

Required scenarios:

1. `GET` request includes path, configured query parameters, and formatted watermark.
2. Basic, Bearer, API Key, and OAuth2 authentication are applied correctly.
3. Configured custom headers are propagated while `Authorization` remains protected.
4. `$.items[*]` extracts multiple records and a root object extracts one record.
5. Missing path, scalar path, malformed JSON, invalid JSONPath, and non-2xx responses fail explicitly.
6. REST extraction integrates with transformation and produces outbox events through the existing orchestrator.
7. JDBC profiles continue using `GenericJdbcAdapter` unchanged.
8. REST profiles require a stable key property and preserve watermark on failure.

WireMock is used for HTTP behavior; Mockito is limited to unavoidable collaborators such as secret resolution, token caching, and resilience execution boundaries.

## Non-goals

- No new REST Outbound behavior.
- No changes to the IntegrationProfile public JSON contract beyond fields already represented by `ExtractionConfig`.
- No new persistence tables or migrations.
- No implementation of SAP, SIGO, Backoffice, or Docker deployment work in this slice.
- No pagination protocol beyond the configured request and response extraction contract; pagination is a follow-up capability.
