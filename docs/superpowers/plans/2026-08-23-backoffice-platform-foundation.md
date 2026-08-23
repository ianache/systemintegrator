# Backoffice Platform Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the Backoffice platform end-to-end — Gateway trusting a second Keycloak realm, an Nx workspace with an Angular Shell + a stub "integration" MicroUI wired together via Native Federation, and a NestJS BFF that owns the OIDC session and proxies authenticated calls to the existing Gateway — so a real admin login reaches the Gateway with zero business screens yet.

**Architecture:** Browser → BFF (NestJS, single origin, serves the built Shell and exposes `/auth/**` + `/bff/api/**`) → Gateway (`middleware:8081`, now trusting both `realms/microservicios` and `realms/Apps`) → existing `app`. The BFF is the only public entry point for the Backoffice; the Gateway's business-API role and its "tenant only from a validated JWT" invariant are unchanged.

**Tech Stack:** Angular (latest, Native Federation via `@angular-architects/native-federation`), Nx monorepo, NestJS + `openid-client` v6 + `express-session` + `connect-redis`, Spring Cloud Gateway (Java 21, existing `gateway/` Maven module), Docker Compose.

**Spec:** `docs/superpowers/specs/2026-08-23-backoffice-microui-architecture-design.md`
**ADRs:** `docs/adrs/0001` through `docs/adrs/0007`

## Global Constraints

- The Gateway's invariant "tenant_id always comes from a validated JWT, never from a client header" must not change (ADR-0003).
- No tenant switching at runtime in this phase — one fixed `tenant_id` per admin session (ADR-0004).
- Tokens (access/refresh) must never reach the browser; only an `HttpOnly` + `Secure` + `SameSite` session cookie does (ADR-0002).
- All new frontend/BFF code lives under `backoffice/` in this repository, as an Nx workspace (ADR-0005).
- The Backoffice is a single public origin: the BFF serves the built Shell and exposes the API under the same origin/port, avoiding cross-origin cookie issues (refines ADR-0006 for this phase; a dedicated static-asset host can be introduced later without changing this architecture).
- No secrets (OIDC client secret, session secret) are committed; they flow through `.env`, the same pattern already used for `KEYCLOAK_ISSUER_URI`.
- `gateway/` is a standalone Maven project (its own `spring-boot-starter-parent`), **not** a module of the root `pom.xml` (root only aggregates `application` and `e2e`). All Gateway Maven commands in this plan use `mvn -f gateway/pom.xml ...`, never `mvn -f gateway/pom.xml ...`.

---

## Task 1: Gateway — deterministic multi-issuer authentication manager resolver

**Files:**
- Create: `gateway/src/main/java/com/cl2/integration/gateway/security/TrustedIssuersAuthenticationManagerResolver.java`
- Test: `gateway/src/test/java/com/cl2/integration/gateway/security/TrustedIssuersAuthenticationManagerResolverTest.java`

**Interfaces:**
- Produces: `TrustedIssuersAuthenticationManagerResolver.from(Map<String, ReactiveJwtDecoder> decodersByIssuer)` → `JwtIssuerReactiveAuthenticationManagerResolver` (implements `ReactiveAuthenticationManagerResolver<ServerWebExchange>`), consumed by Task 2's `GatewaySecurityConfig`.

- [ ] **Step 1: Write the failing test**

```java
package com.cl2.integration.gateway.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.WebFilterChainProxy;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;

class TrustedIssuersAuthenticationManagerResolverTest {

    private static final String ISSUER_MICROSERVICIOS = "https://issuer.example.test/realms/microservicios";
    private static final String ISSUER_APPS = "https://issuer.example.test/realms/Apps";
    private static final UUID TENANT_ID = UUID.fromString("24a4a27e-98ff-4d55-882e-4b3741e4dd3e");

    private static JwtEncoder microserviciosEncoder;
    private static JwtEncoder appsEncoder;
    private static WebTestClient client;

    @BeforeAll
    static void setUp() throws Exception {
        KeyPair microserviciosKeys = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        KeyPair appsKeys = KeyPairGenerator.getInstance("RSA").generateKeyPair();

        microserviciosEncoder = encoder(microserviciosKeys, "microservicios-key");
        appsEncoder = encoder(appsKeys, "apps-key");

        NimbusReactiveJwtDecoder microserviciosDecoder =
                NimbusReactiveJwtDecoder.withPublicKey((RSAPublicKey) microserviciosKeys.getPublic()).build();
        microserviciosDecoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER_MICROSERVICIOS));

        NimbusReactiveJwtDecoder appsDecoder =
                NimbusReactiveJwtDecoder.withPublicKey((RSAPublicKey) appsKeys.getPublic()).build();
        appsDecoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER_APPS));

        var resolver = TrustedIssuersAuthenticationManagerResolver.from(Map.of(
                ISSUER_MICROSERVICIOS, microserviciosDecoder,
                ISSUER_APPS, appsDecoder));

        SecurityWebFilterChain filterChain = ServerHttpSecurity.http()
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> exchanges.anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.authenticationManagerResolver(resolver))
                .build();

        client = WebTestClient
                .bindToRouterFunction(RouterFunctions.route(GET("/**"), request -> ServerResponse.ok().build()))
                .webFilter(new WebFilterChainProxy(filterChain))
                .build();
    }

    @Test
    void acceptsTokenSignedByMicroserviciosRealm() {
        String token = token(microserviciosEncoder, ISSUER_MICROSERVICIOS, "microservicios-key");

        client.get().uri("/api/v1/integration-profiles")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void acceptsTokenSignedByAppsRealm() {
        String token = token(appsEncoder, ISSUER_APPS, "apps-key");

        client.get().uri("/api/v1/integration-profiles")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void rejectsTokenFromUntrustedIssuer() {
        String token = token(microserviciosEncoder, "https://untrusted.example.test/realms/other", "microservicios-key");

        client.get().uri("/api/v1/integration-profiles")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    private static String token(JwtEncoder encoder, String issuer, String keyId) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject("test-user")
                .claim("tenant_id", TENANT_ID.toString())
                .issuedAt(Instant.parse("2026-08-15T00:00:00Z"))
                .expiresAt(Instant.parse("2099-01-01T00:00:00Z"))
                .build();
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).build(), claims)).getTokenValue();
    }

    private static JwtEncoder encoder(KeyPair keyPair, String keyId) {
        RSAKey rsaKey = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyID(keyId)
                .build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(rsaKey)));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -f gateway/pom.xml test -Dtest=TrustedIssuersAuthenticationManagerResolverTest`
Expected: FAIL — compile error, `TrustedIssuersAuthenticationManagerResolver` does not exist.

- [ ] **Step 3: Write the minimal implementation**

```java
package com.cl2.integration.gateway.security;

import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtIssuerReactiveAuthenticationManagerResolver;
import org.springframework.security.oauth2.server.resource.authentication.JwtReactiveAuthenticationManager;

import java.util.Map;
import java.util.stream.Collectors;

final class TrustedIssuersAuthenticationManagerResolver {

    private TrustedIssuersAuthenticationManagerResolver() {
    }

    static JwtIssuerReactiveAuthenticationManagerResolver from(Map<String, ReactiveJwtDecoder> decodersByIssuer) {
        Map<String, ReactiveAuthenticationManager> managersByIssuer = decodersByIssuer.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey,
                        entry -> new JwtReactiveAuthenticationManager(entry.getValue())));
        return new JwtIssuerReactiveAuthenticationManagerResolver(managersByIssuer);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -f gateway/pom.xml test -Dtest=TrustedIssuersAuthenticationManagerResolverTest`
Expected: PASS — 3 tests, no network access (all issuers are locally-signed, no real HTTP call).

- [ ] **Step 5: Commit**

```bash
git add gateway/src/main/java/com/cl2/integration/gateway/security/TrustedIssuersAuthenticationManagerResolver.java gateway/src/test/java/com/cl2/integration/gateway/security/TrustedIssuersAuthenticationManagerResolverTest.java
git commit -m "feat(gateway): add deterministic multi-issuer authentication manager resolver"
```

---

## Task 2: Gateway — wire multi-issuer trust into GatewaySecurityConfig

**Files:**
- Modify: `gateway/src/main/java/com/cl2/integration/gateway/security/GatewaySecurityConfig.java`
- Modify: `gateway/src/main/resources/application.yml`
- Modify: `gateway/src/test/java/com/cl2/integration/gateway/security/GatewaySecurityTest.java`

**Interfaces:**
- Consumes: `TrustedIssuersAuthenticationManagerResolver.from(...)` from Task 1.
- Consumes: `keycloak.issuer-uri` and new `keycloak.apps-issuer-uri` properties.

- [ ] **Step 1: Update `application.yml`'s `qa-e2e` profile**

Replace the `qa-e2e` block (the current `spring.security.oauth2.resourceserver.jwt.issuer-uri` line is removed — it would otherwise make Spring Boot eagerly build an unused, network-calling `ReactiveJwtDecoder` bean at startup even though we now authenticate via a resolver, not `.jwt()`):

```yaml
---
spring:
  config:
    activate:
      on-profile: qa-e2e
keycloak:
  issuer-uri: ${KEYCLOAK_ISSUER_URI:https://oauth2.qa.comsatel.com.pe/realms/microservicios}
  apps-issuer-uri: ${KEYCLOAK_APPS_ISSUER_URI:https://oauth2.qa.comsatel.com.pe/realms/Apps}
```

- [ ] **Step 2: Update the failing test first — extend `GatewaySecurityTest`**

Replace the `TestSecurityConfiguration` inner class (the old `ReactiveJwtDecoder` override no longer plugs into anything, since `.jwt()` is no longer used):

```java
    @TestConfiguration(proxyBeanMethods = false)
    static class TestSecurityConfiguration {

        @Bean
        ReactiveAuthenticationManagerResolver<ServerWebExchange> issuerAuthenticationManagerResolver() {
            return exchange -> Mono.error(new OAuth2AuthenticationException(new OAuth2Error("invalid_token")));
        }
    }
```

Add the required imports to `GatewaySecurityTest.java`:

```java
import org.springframework.security.authentication.ReactiveAuthenticationManagerResolver;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.web.server.ServerWebExchange;
```

Remove the now-unused imports `BadJwtException` and `ReactiveJwtDecoder` from the top of the file.

- [ ] **Step 3: Run the test to verify it fails**

Run: `mvn -f gateway/pom.xml test -Dtest=GatewaySecurityTest`
Expected: FAIL — compile error, `GatewaySecurityConfig` has no `issuerAuthenticationManagerResolver` bean of type `ReactiveAuthenticationManagerResolver<ServerWebExchange>` for the test bean to back off from (Spring context fails to start because `ConditionalOnMissingBean` doesn't exist yet).

- [ ] **Step 4: Implement `GatewaySecurityConfig`**

```java
package com.cl2.integration.gateway.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.ReactiveAuthenticationManagerResolver;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoders;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.server.ServerWebExchange;

import java.util.Map;

@Configuration
@EnableWebFluxSecurity
@Profile("qa-e2e")
public class GatewaySecurityConfig {

    @Bean
    SecurityWebFilterChain springSecurityFilterChain(
            ServerHttpSecurity http,
            ReactiveAuthenticationManagerResolver<ServerWebExchange> issuerAuthenticationManagerResolver) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/actuator/health").permitAll()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationManagerResolver(issuerAuthenticationManagerResolver))
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    ReactiveAuthenticationManagerResolver<ServerWebExchange> issuerAuthenticationManagerResolver(
            @Value("${keycloak.issuer-uri}") String microserviciosIssuer,
            @Value("${keycloak.apps-issuer-uri}") String appsIssuer) {
        Map<String, ReactiveJwtDecoder> decodersByIssuer = Map.of(
                microserviciosIssuer, ReactiveJwtDecoders.fromIssuerLocation(microserviciosIssuer),
                appsIssuer, ReactiveJwtDecoders.fromIssuerLocation(appsIssuer));
        return TrustedIssuersAuthenticationManagerResolver.from(decodersByIssuer);
    }
}
```

- [ ] **Step 5: Run the full Gateway test suite to verify it passes**

Run: `mvn -f gateway/pom.xml test`
Expected: PASS — `GatewaySecurityTest`, `TenantClaimGatewayFilterTest`, `LocalJwtValidationTest`, `TrustedIssuersAuthenticationManagerResolverTest`, `GatewayApplicationTest` all green, no network access.

- [ ] **Step 6: Verify Compose config still renders without secrets**

Run: `docker compose config --quiet`
Expected: exits 0 (the new `keycloak.apps-issuer-uri` default requires no new Compose variable yet — wired to Compose env in Task 12).

- [ ] **Step 7: Commit**

```bash
git add gateway/src/main/java/com/cl2/integration/gateway/security/GatewaySecurityConfig.java gateway/src/main/resources/application.yml gateway/src/test/java/com/cl2/integration/gateway/security/GatewaySecurityTest.java
git commit -m "feat(gateway): trust realms/Apps alongside realms/microservicios for JWT validation"
```

---

## Task 3: Nx workspace scaffolding — Shell, integration-mfe, BFF

**Files:**
- Create: `backoffice/` (Nx workspace root: `nx.json`, `package.json`, `tsconfig.base.json`)
- Create: `backoffice/apps/shell/` (Angular app, generated)
- Create: `backoffice/apps/integration-mfe/` (Angular app, generated)
- Create: `backoffice/apps/bff/` (NestJS app, generated)

**Interfaces:**
- Produces: `nx build shell`, `nx build integration-mfe`, `nx build bff`, `nx test <project>` executors for every later task in this plan.

**Environment note (verified against the Nx CLI actually installed in this environment, v23.1.1 — do not assume an older Nx's defaults; this section reflects the sequence actually validated end-to-end, after two earlier approaches — `create-nx-workspace` with the `apps` preset, and that same preset plus manual AI-agent-file cleanup — both failed for reasons explained below):**

`create-nx-workspace`'s legacy `--preset` values (including `apps`) are implemented as **GitHub template clones**, not generators (the tool logs `Mapping legacy preset 'apps' to template 'nrwl/empty-template'`). This causes two independent problems: (1) the cloned template ships Nx's "TS solution" `tsconfig.base.json` (`"composite": true`) with project references, which `@nx/angular:application` explicitly refuses to scaffold into (`The Angular framework doesn't support a TypeScript setup with project references`) — no combination of `create-nx-workspace` flags changes this, since workspace-shaping flags are inert against a static template clone; and (2) `.claude/`, `.codex/`, `.cursor/`, `.gemini/`, `.opencode/`, `.agents/`, `AGENTS.md`, `CLAUDE.md`, `opencode.json` are checked into that same template repo, so `--aiAgents=none` (which only suppresses *generating additional* agent config) cannot remove them either.

The working alternative is `nx init`, which **is** a generator (not a template clone) and produces a classic, Angular-compatible `tsconfig.base.json` with no project references, while also genuinely honoring `--aiAgents=none` — no manual cleanup step needed.

- [ ] **Step 1: Generate the bare Nx workspace via `nx init`**

Run, from the repository root:

```bash
mkdir backoffice
cd backoffice
```

`nx init` in a truly empty directory silently falls back to Nx's standalone `.nx` wrapper install (producing `nx`, `nx.bat`, `.nx/nxw.js`, an `"installation"` block in `nx.json`, and no `package.json`) even with `--useDotNxInstallation=false`. Avoid this by seeding a minimal `package.json` first:

```json
{
  "name": "backoffice",
  "version": "0.0.0",
  "private": true
}
```

Write that to `backoffice/package.json`, then:

```bash
npm install -D nx@23.1.1 @nx/angular@23.1.1 @nx/nest@23.1.1
npx nx init --interactive=false --aiAgents=none --nxCloud=false --plugins=skip
```

Expected: `nx.json`, `tsconfig.base.json` (no `"composite": true`, no `references`) created alongside `package.json`. No `apps/`, `packages/`, or AI-agent dotfiles/AGENTS.md/CLAUDE.md/opencode.json should appear — if any do, stop and report BLOCKED with the exact output.

**Verify `.gitignore` exists and ignores `node_modules` before proceeding to any later step.** `nx init` only reliably emits a `.gitignore` when it also creates `package.json` itself; since this sequence pre-seeds `package.json`, check explicitly:

```bash
cat .gitignore 2>&1
git status --short | grep -c node_modules
```

If `.gitignore` is missing or the `node_modules` count is nonzero, create/fix `backoffice/.gitignore` to include at minimum `node_modules/`, `dist/`, `.nx/cache/`, `.angular/cache/` before continuing — this must be caught here, not at the Step 8 commit, where staging tens of thousands of `node_modules` files would be easy to miss.

- [ ] **Step 2: Check the exact generator flags before using them**

Run: `npx nx g @nx/angular:application --help` and `npx nx g @nx/nest:application --help`. Modern Nx generators typically take the target directory as the first positional argument (e.g. `npx nx g @nx/angular:application apps/shell`), deriving the project name from the last path segment — but confirm this against the actual `--help` output for the installed version rather than assuming. Note the exact flags for: standalone components, a CSS stylesheet, routing, and E2E test runner (Angular), and for Nest, the equivalent app-generation flags.

- [ ] **Step 3: Generate the Shell app**

Run the `@nx/angular:application` generator targeting `apps/shell` (using the flags confirmed in Step 2) with: standalone components, CSS stylesheet, routing enabled, Playwright as the e2e test runner, `--interactive=false`. Example (adjust flag names only if Step 2's `--help` output disagrees):

```bash
npx nx g @nx/angular:application apps/shell --style=css --routing --standalone --e2eTestRunner=playwright --interactive=false
```

Expected: `backoffice/apps/shell` created (and `backoffice/apps/shell-e2e` if the e2e generator ran). Verify with `npx nx build shell && npx nx test shell` — both PASS.

- [ ] **Step 4: Generate the integration-mfe Angular app**

Run the same generator targeting `apps/integration-mfe`, no e2e runner needed for this one:

```bash
npx nx g @nx/angular:application apps/integration-mfe --style=css --routing --standalone --e2eTestRunner=none --interactive=false
```

Expected: `backoffice/apps/integration-mfe` created.

- [ ] **Step 5: Generate the BFF NestJS app**

Run the `@nx/nest:application` generator (using the flags confirmed in Step 2) targeting `apps/bff`. The generator's `--unitTestRunner` choices are `jest|none` and default to `none` — pass `jest` explicitly, otherwise `nx test bff` (required by this task's Interfaces contract, and relied on by later BFF tasks) has no target to run:

```bash
npx nx g @nx/nest:application apps/bff --unitTestRunner=jest --interactive=false
```

Expected: `backoffice/apps/bff` created with a default NestJS `AppModule`/`AppController` and a working `jest` test target.

**Known necessary fix:** running `npx nx test bff` at this point may fail with a TypeScript error (`TS2591`, `Cannot find name 'require'` or similar) in `jest.config.cts`, because `apps/bff/tsconfig.json` has no `types` array and ts-node resolves the app's own tsconfig rather than the workspace root's. Fix by adding `"types": ["node"]` to the `compilerOptions` of `backoffice/apps/bff/tsconfig.json` (scoped to `bff` only — do not add this to `tsconfig.base.json` or to the Angular apps' tsconfigs, which must not gain Node types). Confirm `npx nx test bff` passes after this change.

- [ ] **Step 6: Verify all three projects build**

Run: `npx nx run-many -t build -p shell,integration-mfe,bff`
Expected: PASS — three separate `dist/` outputs under `backoffice/dist/`.

- [ ] **Step 7: Commit**

**Never use `git commit --amend`, `git rebase`, or `git reset` on any commit other than your own uncommitted work in this task.** This repository has prior commits from earlier tasks (Task 1, Task 2) already reviewed and closed — do not alter them under any circumstance. Record the current HEAD before committing, so you can immediately tell if anything unexpected touches it:

```bash
git log -1 --oneline
git add backoffice/
git status
```

Review the `git status` output: it must show only new files under `backoffice/`, nothing under `gateway/` or any other existing top-level directory. Only then:

```bash
git commit -m "chore(backoffice): scaffold Nx workspace with shell, integration-mfe, bff"
git log -2 --oneline
```

The second `git log` must show your new commit on top, with the pre-commit `git log -1` output as its immediate parent, unchanged. If the parent commit's hash or subject differs from what you recorded before committing, something rewrote history — stop immediately and report BLOCKED rather than investigating further yourself.

---

## Task 4: shell-contracts library — Shell↔MicroUI route manifest

**Files:**
- Create: `backoffice/libs/shell-contracts/src/index.ts`
- Create: `backoffice/libs/shell-contracts/src/lib/micro-ui-route.ts`
- Test: `backoffice/libs/shell-contracts/src/lib/micro-ui-route.spec.ts`

**Interfaces:**
- Produces: `MicroUiRouteManifest` type and `buildMicroUiRoute(manifest: MicroUiRouteManifest): Route` (Angular `Route`), consumed by Task 5's Shell routing.

**Dependency note (moved up from Task 5):** Step 4's implementation imports `@angular-architects/native-federation` for `loadRemoteModule`. That package was originally planned to be installed in Task 5, but this task needs it at compile time — install it here instead, so Task 4 is self-contained. Task 5 no longer installs it (already present).

- [ ] **Step 1: Install `@angular-architects/native-federation`**

Run, from `backoffice/`:

```bash
npm install @angular-architects/native-federation@22.0.6
```

**Pinned, not `@latest` (verified):** `@latest` (`22.1.1` at the time this was checked) requires `@angular/build@~22.1.0` as a peer, but this workspace is pinned to `@angular/build@~22.0.4` (set by Task 3, already reviewed and closed — not something to bump for this task). `22.0.6` declares `@angular/build@~22.0.0` as its peer, which matches. If `npm install` still reports a peer-dependency conflict, stop and report BLOCKED with the exact error rather than forcing it with `--legacy-peer-deps` or `--force`.

- [ ] **Step 2: Generate the library**

Run: `cd backoffice && npx nx g @nx/js:lib libs/shell-contracts --bundler=none --unitTestRunner=jest`

**Known necessary fix (same pattern as Task 3's `apps/bff/tsconfig.json`):** the generated `libs/shell-contracts/tsconfig.json` will likely be missing a `types` array, which breaks Jest's parsing of `jest.config.cts` before any test runs. If `npx nx test shell-contracts` fails with a TS error pointing at `jest.config.cts` before you've even written Step 4's code, add `"types": ["node"]` to `libs/shell-contracts/tsconfig.json`'s `compilerOptions` (scoped to this library only, matching how `apps/bff/tsconfig.json` was already fixed in Task 3) and retry.

- [ ] **Step 3: Write the failing test**

```typescript
// backoffice/libs/shell-contracts/src/lib/micro-ui-route.spec.ts
import { buildMicroUiRoute } from './micro-ui-route';

describe('buildMicroUiRoute', () => {
  it('builds a lazy Angular route pointing at the remote exposed module', () => {
    const route = buildMicroUiRoute({
      path: 'integration',
      remoteName: 'integrationMfe',
      remoteEntry: 'http://localhost:4202/remoteEntry.json',
      exposedModule: './Routes',
    });

    expect(route.path).toBe('integration');
    expect(typeof route.loadChildren).toBe('function');
  });
});
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `npx nx test shell-contracts`
Expected: FAIL — `micro-ui-route` module does not exist.

- [ ] **Step 5: Write the minimal implementation**

```typescript
// backoffice/libs/shell-contracts/src/lib/micro-ui-route.ts
import { Route } from '@angular/router';

export interface MicroUiRouteManifest {
  path: string;
  remoteName: string;
  remoteEntry: string;
  exposedModule: string;
}

export function buildMicroUiRoute(manifest: MicroUiRouteManifest): Route {
  return {
    path: manifest.path,
    loadChildren: () =>
      import('@angular-architects/native-federation').then(({ loadRemoteModule }) =>
        loadRemoteModule({
          remoteName: manifest.remoteName,
          remoteEntry: manifest.remoteEntry,
          exposedModule: manifest.exposedModule,
        }).then((m) => m.routes),
      ),
  };
}
```

```typescript
// backoffice/libs/shell-contracts/src/index.ts
export * from './lib/micro-ui-route';
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `npx nx test shell-contracts`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backoffice/libs/shell-contracts backoffice/package.json backoffice/package-lock.json
git commit -m "feat(shell-contracts): add MicroUI route manifest contract"
```

---

## Task 5: Native Federation — shell as dynamic host, integration-mfe as remote

**Files:**
- Modify: `backoffice/apps/shell/` (federation config + routing, generated/edited)
- Modify: `backoffice/apps/integration-mfe/` (federation config + exposed routes, generated/edited)
- Test: `backoffice/apps/shell-e2e/src/shell.spec.ts`

**Interfaces:**
- Consumes: `buildMicroUiRoute` from Task 4.
- Produces: a running Shell at `http://localhost:4201` that lazily loads `integration-mfe` at `http://localhost:4202/remoteEntry.json` under route `/integration`.

**Verified deviations (implementation actually landed these; steps below are kept for historical record, not re-execution):** federation config files generated as `.mjs`, not `.js`; the `ng g` CLI is unusable in this environment (Node 22.15.0, Angular CLI requires ≥22.22.3) — use `nx g` for the same schematic instead; the workspace import alias is `shell-contracts`, not `@backoffice/shell-contracts` (no npm scope configured, consistent with Task 4's review finding); `remoteName` must exactly match the Nx project name `integration-mfe`, not a camelCase variant. **Most significant:** `shareAll()` in `federation.config.mjs` treats `shell-contracts` (a workspace TS-path-alias library, not an npm package) as a shared external, emitting it as its own bundle carrying a second, never-initialized federation runtime — `loadRemoteModule` then hangs silently with no error. Fixed with `skip: ['shell-contracts']`. This applies to any future workspace `libs/*` alias consumed by a federated app. Also: the generated `federation.manifest.json` guessed wrong remote ports (including `bff`, a NestJS API, on port 4200) and was emptied; `apps/shell-e2e/playwright.config.mts`'s `@nx/playwright/preset` import fails under Playwright's ESM loader in this environment (a pre-existing Nx/Playwright interaction bug, not introduced by this task) — worked around by inlining the preset's defaults directly and dropping the Nx import.

- [ ] **Step 1: Initialize the Shell as a dynamic host**

`@angular-architects/native-federation` is already installed (moved to Task 4, since `shell-contracts` needed it at compile time). Run, from `backoffice/`:

```bash
npx ng g @angular-architects/native-federation:init --project=shell --port=4201 --type=dynamic-host
```

Expected: `backoffice/apps/shell/federation.config.js` created; `project.json` for `shell` gains federation build/serve targets, and its `serve` target's default port should become `4201` (confirm this — see the note on Step 6 below; Nx's default Angular app scaffold puts both `shell` and `integration-mfe` on port `4200`, and this generator's `--port` flag is what's expected to resolve that collision).

- [ ] **Step 2: Initialize integration-mfe as a remote**

Run: `npx ng g @angular-architects/native-federation:init --project=integration-mfe --port=4202 --type=remote`
Expected: `backoffice/apps/integration-mfe/federation.config.js` created, exposing a placeholder module.

- [ ] **Step 3: Expose a `Routes` module from integration-mfe**

```typescript
// backoffice/apps/integration-mfe/src/app/remote-entry/entry.routes.ts
import { Routes } from '@angular/router';
import { IntegrationRootComponent } from './integration-root.component';

export const routes: Routes = [{ path: '', component: IntegrationRootComponent }];
```

```typescript
// backoffice/apps/integration-mfe/src/app/remote-entry/integration-root.component.ts
import { Component } from '@angular/core';

@Component({
  selector: 'app-integration-root',
  standalone: true,
  template: `<p data-testid="integration-mfe-loaded">Integration MicroUI loaded</p>`,
})
export class IntegrationRootComponent {}
```

Edit `backoffice/apps/integration-mfe/federation.config.js` so `exposes` includes:

```javascript
exposes: {
  './Routes': './apps/integration-mfe/src/app/remote-entry/entry.routes.ts',
},
```

- [ ] **Step 4: Wire the Shell route to the remote using shell-contracts**

```typescript
// backoffice/apps/shell/src/app/app.routes.ts
import { Routes } from '@angular/router';
import { buildMicroUiRoute } from '@backoffice/shell-contracts';

export const appRoutes: Routes = [
  buildMicroUiRoute({
    path: 'integration',
    remoteName: 'integrationMfe',
    remoteEntry: 'http://localhost:4202/remoteEntry.json',
    exposedModule: './Routes',
  }),
];
```

- [ ] **Step 5: Write the failing e2e test proving the MicroUI loads through the Shell**

```typescript
// backoffice/apps/shell-e2e/src/shell.spec.ts
import { test, expect } from '@playwright/test';

test('shell loads the integration MicroUI via Native Federation', async ({ page }) => {
  await page.goto('/integration');
  await expect(page.getByTestId('integration-mfe-loaded')).toHaveText('Integration MicroUI loaded');
});
```

- [ ] **Step 6: Run the e2e test to verify it fails**

**Before running this, confirm `shell` and `integration-mfe` are configured to serve on different ports** (`4201` and `4202` respectively, matching the `--port` flags from Steps 1-2 and the URL used in Step 4). Check each project's `project.json` `serve` target. Nx's default Angular app scaffold puts every app's `serve` target on port `4200` by default — if the federation `:init` generator did not override this for one or both projects, set it explicitly (e.g. `"options": { "port": 4201 }` on `shell`'s `serve` target, `4202` on `integration-mfe`'s) before starting both dev servers, or the second one to start will fail to bind and the e2e test will get confusing connection errors instead of a clean federation-wiring failure.

Run (two terminals, or an Nx `run-many` serve target if configured): `npx nx serve integration-mfe & npx nx serve shell & npx nx e2e shell-e2e`
Expected: FAIL — route `/integration` does not resolve yet (federation wiring incomplete) or `remoteEntry.json` not found.

- [ ] **Step 7: Complete the wiring until the test passes**

Ensure `backoffice/apps/shell/federation.config.js` does **not** statically list `integration-mfe` (dynamic host resolves remotes at runtime from the URL passed to `loadRemoteModule`), and that `appRoutes` from Step 4 is registered in the Shell's root router config (`app.config.ts` → `provideRouter(appRoutes)`).

- [ ] **Step 8: Run the e2e test to verify it passes**

Run: `npx nx e2e shell-e2e`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add backoffice/apps/shell backoffice/apps/integration-mfe backoffice/apps/shell-e2e
git commit -m "feat(backoffice): wire shell and integration-mfe via Native Federation"
```

---

## Task 6: BFF — Redis-backed session module

**Files:**
- Create: `backoffice/apps/bff/src/session/session.module.ts`
- Modify: `backoffice/apps/bff/src/main.ts`
- Test: `backoffice/apps/bff/src/session/session.module.spec.ts`

**Interfaces:**
- Produces: `configureSession(app: INestApplication, config: ConfigService): void`, applying `express-session` middleware backed by Redis; consumed by `main.ts` and by every controller in Tasks 7-10 via `req.session`.

- [ ] **Step 1: Install dependencies**

Run: `cd backoffice && npm install express-session connect-redis redis @nestjs/config && npm install -D @types/express-session supertest @types/supertest`

**Verified necessary, not in the original install line:** `supertest`/`@types/supertest` (the test below imports them, and neither existed anywhere in the workspace yet) — folded into the command above. Also: `main.ts` calls `app.get(ConfigService)`, which throws at boot unless `ConfigModule` is registered — add `ConfigModule.forRoot({ isGlobal: true })` to `backoffice/apps/bff/src/app/app.module.ts`'s `imports` if it isn't already there.

- [ ] **Step 2: Write the failing test**

```typescript
// backoffice/apps/bff/src/session/session.module.spec.ts
import { Test } from '@nestjs/testing';
import { ConfigModule, ConfigService } from '@nestjs/config';
import * as request from 'supertest';
import { INestApplication } from '@nestjs/common';
import { Controller, Get, Req } from '@nestjs/common';
import { configureSession } from './configure-session';

@Controller()
class ProbeController {
  @Get('probe')
  probe(@Req() req: any) {
    req.session.hits = (req.session.hits || 0) + 1;
    return { hits: req.session.hits };
  }
}

describe('configureSession', () => {
  let app: INestApplication;

  beforeAll(async () => {
    process.env.BFF_SESSION_SECRET = 'test-secret';
    process.env.REDIS_URL = 'redis://localhost:6379';
    const moduleRef = await Test.createTestingModule({
      imports: [ConfigModule.forRoot({ isGlobal: true })],
      controllers: [ProbeController],
    }).compile();
    app = moduleRef.createNestApplication();
    configureSession(app, app.get(ConfigService));
    await app.init();
  });

  afterAll(async () => {
    await app.close();
  });

  it('persists session state across requests via the session cookie', async () => {
    const agent = request.agent(app.getHttpServer());
    const first = await agent.get('/probe');
    const second = await agent.get('/probe');

    expect(first.body.hits).toBe(1);
    expect(second.body.hits).toBe(2);
  });
});
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `npx nx test bff`
Expected: FAIL — `./configure-session` does not exist.

- [ ] **Step 4: Write the minimal implementation**

```typescript
// backoffice/apps/bff/src/session/configure-session.ts
import { INestApplication } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import session from 'express-session';
import { createClient } from 'redis';
import { RedisStore } from 'connect-redis';

export function configureSession(app: INestApplication, config: ConfigService): void {
  const redisClient = createClient({ url: config.getOrThrow('REDIS_URL') });
  redisClient.connect().catch((error) => {
    throw error;
  });

  app.use(
    session({
      store: new RedisStore({ client: redisClient, prefix: 'backoffice-session:' }),
      secret: config.getOrThrow('BFF_SESSION_SECRET'),
      resave: false,
      saveUninitialized: false,
      cookie: {
        httpOnly: true,
        secure: config.get('NODE_ENV') === 'production',
        sameSite: 'lax',
        maxAge: 8 * 60 * 60 * 1000,
      },
    }),
  );
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `npx nx test bff`
Expected: PASS. (Requires a local Redis reachable at `REDIS_URL`; the repo's `docker compose up -d redis` already provides one.)

- [ ] **Step 6: Wire it into `main.ts`**

```typescript
// backoffice/apps/bff/src/main.ts (relevant excerpt)
import { NestFactory } from '@nestjs/core';
import { ConfigService } from '@nestjs/config';
import { AppModule } from './app/app.module';
import { configureSession } from './session/configure-session';

async function bootstrap() {
  const app = await NestFactory.create(AppModule);
  configureSession(app, app.get(ConfigService));
  await app.listen(process.env.PORT ?? 4000);
}
bootstrap();
```

- [ ] **Step 7: Commit**

```bash
git add backoffice/apps/bff/src/session backoffice/apps/bff/src/main.ts backoffice/package.json backoffice/package-lock.json
git commit -m "feat(bff): add Redis-backed session middleware"
```

---

## Task 7: BFF — OIDC login redirect (Authorization Code + PKCE)

**Files:**
- Create: `backoffice/apps/bff/src/auth/auth.service.ts`
- Create: `backoffice/apps/bff/src/auth/auth.controller.ts`
- Create: `backoffice/apps/bff/src/auth/auth.module.ts`
- Test: `backoffice/apps/bff/src/auth/auth.controller.spec.ts`

**Interfaces:**
- Consumes: `req.session` from Task 6.
- Produces: `GET /auth/login` (302 redirect to Keycloak); `AuthService.buildAuthorizationUrl()` returning `{ url, codeVerifier, state }`, consumed by Task 8's callback handler.

- [ ] **Step 1: Install `openid-client`**

Run: `cd backoffice && npm install openid-client`

- [ ] **Step 2: Write the failing test**

```typescript
// backoffice/apps/bff/src/auth/auth.controller.spec.ts
import { Test } from '@nestjs/testing';
import { AuthController } from './auth.controller';
import { AuthService } from './auth.service';

describe('AuthController.login', () => {
  it('stores the PKCE verifier and state in the session and redirects to Keycloak', async () => {
    const authService = {
      buildAuthorizationUrl: jest.fn().mockResolvedValue({
        url: 'https://oauth2.qa.comsatel.com.pe/realms/Apps/protocol/openid-connect/auth?...',
        codeVerifier: 'verifier-123',
        state: 'state-abc',
      }),
    };
    const moduleRef = await Test.createTestingModule({
      controllers: [AuthController],
      providers: [{ provide: AuthService, useValue: authService }],
    }).compile();
    const controller = moduleRef.get(AuthController);

    const req: any = { session: {} };
    const res: any = { redirect: jest.fn() };

    await controller.login(req, res);

    expect(req.session.oidc).toEqual({ codeVerifier: 'verifier-123', state: 'state-abc' });
    expect(res.redirect).toHaveBeenCalledWith(
      'https://oauth2.qa.comsatel.com.pe/realms/Apps/protocol/openid-connect/auth?...',
    );
  });
});
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `npx nx test bff`
Expected: FAIL — `AuthController`/`AuthService` do not exist.

- [ ] **Step 4: Implement `AuthService.buildAuthorizationUrl`**

```typescript
// backoffice/apps/bff/src/auth/auth.service.ts
import { Injectable } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import * as client from 'openid-client';

@Injectable()
export class AuthService {
  private configuration?: client.Configuration;

  constructor(private readonly config: ConfigService) {}

  private async getConfiguration(): Promise<client.Configuration> {
    if (!this.configuration) {
      this.configuration = await client.discovery(
        new URL(this.config.getOrThrow('KEYCLOAK_APPS_ISSUER_URI')),
        this.config.getOrThrow('BFF_OIDC_CLIENT_ID'),
        this.config.getOrThrow('BFF_OIDC_CLIENT_SECRET'),
      );
    }
    return this.configuration;
  }

  async buildAuthorizationUrl(): Promise<{ url: string; codeVerifier: string; state: string }> {
    const configuration = await this.getConfiguration();
    const codeVerifier = client.randomPKCECodeVerifier();
    const codeChallenge = await client.calculatePKCECodeChallenge(codeVerifier);
    const state = client.randomState();
    const url = client.buildAuthorizationUrl(configuration, {
      redirect_uri: `${this.config.getOrThrow('BFF_PUBLIC_URL')}/auth/callback`,
      scope: 'openid profile',
      code_challenge: codeChallenge,
      code_challenge_method: 'S256',
      state,
    });
    return { url: url.href, codeVerifier, state };
  }
}
```

- [ ] **Step 5: Implement `AuthController.login`**

```typescript
// backoffice/apps/bff/src/auth/auth.controller.ts
import { Controller, Get, Req, Res } from '@nestjs/common';
import type { Request, Response } from 'express';
import { AuthService } from './auth.service';

@Controller('auth')
export class AuthController {
  constructor(private readonly authService: AuthService) {}

  @Get('login')
  async login(@Req() req: Request, @Res() res: Response) {
    const { url, codeVerifier, state } = await this.authService.buildAuthorizationUrl();
    (req.session as any).oidc = { codeVerifier, state };
    res.redirect(url);
  }
}
```

```typescript
// backoffice/apps/bff/src/auth/auth.module.ts
import { Module } from '@nestjs/common';
import { AuthController } from './auth.controller';
import { AuthService } from './auth.service';

@Module({
  controllers: [AuthController],
  providers: [AuthService],
  exports: [AuthService],
})
export class AuthModule {}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `npx nx test bff`
Expected: PASS.

- [ ] **Step 7: Register `AuthModule` in `AppModule` and commit**

```bash
git add backoffice/apps/bff/src/auth backoffice/apps/bff/src/app/app.module.ts backoffice/package.json backoffice/package-lock.json
git commit -m "feat(bff): add OIDC login redirect with PKCE"
```

---

## Task 8: BFF — OIDC callback (token exchange) and session write

**Files:**
- Modify: `backoffice/apps/bff/src/auth/auth.service.ts`
- Modify: `backoffice/apps/bff/src/auth/auth.controller.ts`
- Test: `backoffice/apps/bff/src/auth/auth.controller.spec.ts`

**Interfaces:**
- Consumes: `req.session.oidc` written by Task 7.
- Produces: `req.session.tokens = { access_token, refresh_token, id_token, expires_at }`, consumed by Task 9 (session status) and Task 10 (authenticated proxy).

- [ ] **Step 1: Write the failing test**

```typescript
// append to auth.controller.spec.ts
describe('AuthController.callback', () => {
  it('exchanges the code for tokens and stores them in the session', async () => {
    const authService = {
      exchangeCode: jest.fn().mockResolvedValue({
        access_token: 'access-123',
        refresh_token: 'refresh-123',
        id_token: 'id-123',
        expires_at: 1893456000,
      }),
    };
    const moduleRef = await Test.createTestingModule({
      controllers: [AuthController],
      providers: [{ provide: AuthService, useValue: authService }],
    }).compile();
    const controller = moduleRef.get(AuthController);

    const req: any = {
      session: { oidc: { codeVerifier: 'verifier-123', state: 'state-abc' } },
      originalUrl: '/auth/callback?code=abc&state=state-abc',
      protocol: 'http',
      get: () => 'localhost:4000',
    };
    const res: any = { redirect: jest.fn(), status: jest.fn().mockReturnThis(), send: jest.fn() };

    await controller.callback(req, res);

    expect(req.session.tokens.access_token).toBe('access-123');
    expect(req.session.oidc).toBeUndefined();
    expect(res.redirect).toHaveBeenCalledWith('/');
  });

  it('rejects a callback with a mismatched state', async () => {
    const authService = { exchangeCode: jest.fn() };
    const moduleRef = await Test.createTestingModule({
      controllers: [AuthController],
      providers: [{ provide: AuthService, useValue: authService }],
    }).compile();
    const controller = moduleRef.get(AuthController);

    const req: any = {
      session: { oidc: { codeVerifier: 'verifier-123', state: 'state-abc' } },
      originalUrl: '/auth/callback?code=abc&state=WRONG',
      protocol: 'http',
      get: () => 'localhost:4000',
    };
    const res: any = { redirect: jest.fn(), status: jest.fn().mockReturnThis(), send: jest.fn() };

    await controller.callback(req, res);

    expect(authService.exchangeCode).not.toHaveBeenCalled();
    expect(res.status).toHaveBeenCalledWith(400);
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `npx nx test bff`
Expected: FAIL — `AuthController.callback` does not exist.

- [ ] **Step 3: Implement `AuthService.exchangeCode`**

```typescript
// append to auth.service.ts
  async exchangeCode(currentUrl: URL, codeVerifier: string, expectedState: string) {
    const configuration = await this.getConfiguration();
    const tokens = await client.authorizationCodeGrant(configuration, currentUrl, {
      pkceCodeVerifier: codeVerifier,
      expectedState,
    });
    return {
      access_token: tokens.access_token,
      refresh_token: tokens.refresh_token,
      id_token: tokens.id_token,
      expires_at: Math.floor(Date.now() / 1000) + (tokens.expires_in ?? 0),
    };
  }
```

- [ ] **Step 4: Implement `AuthController.callback`**

```typescript
// append to auth.controller.ts
  @Get('callback')
  async callback(@Req() req: Request, @Res() res: Response) {
    const pending = (req.session as any).oidc;
    const currentUrl = new URL(req.originalUrl, `${req.protocol}://${req.get('host')}`);
    const state = currentUrl.searchParams.get('state');

    if (!pending || pending.state !== state) {
      res.status(400).send('Invalid OIDC state');
      return;
    }

    const tokens = await this.authService.exchangeCode(currentUrl, pending.codeVerifier, pending.state);
    (req.session as any).tokens = tokens;
    delete (req.session as any).oidc;
    res.redirect('/');
  }
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `npx nx test bff`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backoffice/apps/bff/src/auth
git commit -m "feat(bff): complete OIDC callback and persist tokens in session"
```

---

## Task 9: BFF — session status endpoint, session guard, and logout

**Files:**
- Modify: `backoffice/apps/bff/src/auth/auth.controller.ts`
- Create: `backoffice/apps/bff/src/auth/session-auth.guard.ts`
- Test: `backoffice/apps/bff/src/auth/session-auth.guard.spec.ts`
- Test: append to `backoffice/apps/bff/src/auth/auth.controller.spec.ts`

**Interfaces:**
- Consumes: `req.session.tokens` from Task 8.
- Produces: `SessionAuthGuard` (NestJS `CanActivate`), consumed by Task 10's proxy controller; `GET /auth/session` → `{ authenticated: boolean, tenantId?: string, expiresAt?: number }`, consumed by Task 11's Shell header.

- [ ] **Step 1: Write the failing guard test**

```typescript
// backoffice/apps/bff/src/auth/session-auth.guard.spec.ts
import { ExecutionContext } from '@nestjs/common';
import { SessionAuthGuard } from './session-auth.guard';

function contextWithSession(session: any): ExecutionContext {
  return {
    switchToHttp: () => ({ getRequest: () => ({ session }) }),
  } as unknown as ExecutionContext;
}

describe('SessionAuthGuard', () => {
  const guard = new SessionAuthGuard();

  it('allows requests with a token in session', () => {
    expect(guard.canActivate(contextWithSession({ tokens: { access_token: 'x' } }))).toBe(true);
  });

  it('rejects requests without a session', () => {
    expect(guard.canActivate(contextWithSession({}))).toBe(false);
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `npx nx test bff`
Expected: FAIL — `SessionAuthGuard` does not exist.

- [ ] **Step 3: Implement `SessionAuthGuard`**

```typescript
// backoffice/apps/bff/src/auth/session-auth.guard.ts
import { CanActivate, ExecutionContext, Injectable } from '@nestjs/common';

@Injectable()
export class SessionAuthGuard implements CanActivate {
  canActivate(context: ExecutionContext): boolean {
    const request = context.switchToHttp().getRequest();
    return Boolean(request.session?.tokens?.access_token);
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `npx nx test bff`
Expected: PASS.

- [ ] **Step 5: Write the failing test for `/auth/session` and `/auth/logout`**

```typescript
// append to auth.controller.spec.ts
describe('AuthController.session', () => {
  it('reports authenticated: false without tokens', () => {
    const controller = new AuthController({} as AuthService);
    const req: any = { session: {} };

    expect(controller.session(req)).toEqual({ authenticated: false });
  });

  it('reports tenantId and expiresAt from the decoded id_token when authenticated', () => {
    const idToken = [
      Buffer.from(JSON.stringify({ alg: 'none' })).toString('base64url'),
      Buffer.from(JSON.stringify({ tenant_id: 't-1' })).toString('base64url'),
      '',
    ].join('.');
    const controller = new AuthController({} as AuthService);
    const req: any = { session: { tokens: { id_token: idToken, expires_at: 1893456000 } } };

    expect(controller.session(req)).toEqual({ authenticated: true, tenantId: 't-1', expiresAt: 1893456000 });
  });
});

describe('AuthController.logout', () => {
  it('destroys the session and redirects to the Keycloak logout endpoint', () => {
    const authService = { buildLogoutUrl: jest.fn().mockReturnValue('https://keycloak/logout') };
    const controller = new AuthController(authService as unknown as AuthService);
    const destroy = jest.fn((cb: () => void) => cb());
    const req: any = { session: { tokens: { id_token: 'id-123' }, destroy } };
    const res: any = { redirect: jest.fn() };

    controller.logout(req, res);

    expect(destroy).toHaveBeenCalled();
    expect(authService.buildLogoutUrl).toHaveBeenCalledWith('id-123');
    expect(res.redirect).toHaveBeenCalledWith('https://keycloak/logout');
  });
});
```

- [ ] **Step 6: Run the tests to verify they fail**

Run: `npx nx test bff`
Expected: FAIL — `session`/`logout` methods and `AuthService.buildLogoutUrl` do not exist.

- [ ] **Step 7: Implement `AuthService.buildLogoutUrl` and the controller methods**

```typescript
// append to auth.service.ts
  buildLogoutUrl(idTokenHint?: string): string {
    const issuer = this.config.getOrThrow('KEYCLOAK_APPS_ISSUER_URI');
    const postLogoutRedirectUri = this.config.getOrThrow('BFF_PUBLIC_URL');
    const url = new URL(`${issuer}/protocol/openid-connect/logout`);
    if (idTokenHint) url.searchParams.set('id_token_hint', idTokenHint);
    url.searchParams.set('post_logout_redirect_uri', postLogoutRedirectUri);
    return url.href;
  }
```

```typescript
// append to auth.controller.ts
  @Get('session')
  session(@Req() req: Request) {
    const tokens = (req.session as any).tokens;
    if (!tokens) {
      return { authenticated: false };
    }
    const payload = JSON.parse(Buffer.from(tokens.id_token.split('.')[1], 'base64url').toString('utf8'));
    return { authenticated: true, tenantId: payload.tenant_id, expiresAt: tokens.expires_at };
  }

  @Get('logout')
  logout(@Req() req: Request, @Res() res: Response) {
    const idToken = (req.session as any).tokens?.id_token;
    const logoutUrl = this.authService.buildLogoutUrl(idToken);
    req.session.destroy(() => {
      res.redirect(logoutUrl);
    });
  }
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `npx nx test bff`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add backoffice/apps/bff/src/auth
git commit -m "feat(bff): add session status endpoint, session guard, and logout"
```

---

## Task 10: BFF — authenticated proxy to the Gateway

**Files:**
- Create: `backoffice/apps/bff/src/gateway-proxy/gateway-proxy.controller.ts`
- Create: `backoffice/apps/bff/src/gateway-proxy/gateway-proxy.module.ts`
- Test: `backoffice/apps/bff/src/gateway-proxy/gateway-proxy.controller.spec.ts`

**Interfaces:**
- Consumes: `SessionAuthGuard` from Task 9, `req.session.tokens.access_token`.
- Produces: `ALL /bff/api/*` forwarded to `${GATEWAY_URI}/api/*` with `Authorization: Bearer <access_token>`, consumed by the Shell/MicroUI HTTP client in later plans.

- [ ] **Step 1: Install the proxy dependency**

Run: `cd backoffice && npm install http-proxy-middleware`

**Design note:** the proxy is a Nest **controller** method guarded with `@UseGuards(SessionAuthGuard)` — not raw Express middleware registered via `MiddlewareConsumer`, and not a global `APP_GUARD`. Middleware that terminates the response (as `http-proxy-middleware` does) runs *before* Nest's guard pipeline, so a guard can never actually protect a middleware-only route; and `APP_GUARD` would apply to every route in the app, including `/auth/login`, causing a lockout. A controller-scoped guard avoids both problems and is what `overrideGuard` in the test below actually exercises.

- [ ] **Step 2: Write the failing test**

```typescript
// backoffice/apps/bff/src/gateway-proxy/gateway-proxy.controller.spec.ts
import { Test } from '@nestjs/testing';
import { INestApplication, ExecutionContext } from '@nestjs/common';
import * as request from 'supertest';
import { GatewayProxyModule } from './gateway-proxy.module';
import { SessionAuthGuard } from '../auth/session-auth.guard';

describe('GatewayProxyController (guard)', () => {
  let app: INestApplication;

  beforeAll(async () => {
    const moduleRef = await Test.createTestingModule({ imports: [GatewayProxyModule] })
      .overrideGuard(SessionAuthGuard)
      .useValue({ canActivate: (context: ExecutionContext) => Boolean(context.switchToHttp().getRequest().session?.tokens) })
      .compile();
    app = moduleRef.createNestApplication();
    await app.init();
  });

  afterAll(async () => app.close());

  it('rejects requests without a session', async () => {
    await request(app.getHttpServer()).get('/bff/api/v1/integration-profiles').expect(403);
  });
});
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `npx nx test bff`
Expected: FAIL — `GatewayProxyModule` does not exist.

- [ ] **Step 4: Implement the proxy controller and module**

```typescript
// backoffice/apps/bff/src/gateway-proxy/gateway-proxy.controller.ts
import { All, Controller, Req, Res, UseGuards } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import type { Request, Response } from 'express';
import { createProxyMiddleware } from 'http-proxy-middleware';
import { SessionAuthGuard } from '../auth/session-auth.guard';

@Controller('bff/api')
@UseGuards(SessionAuthGuard)
export class GatewayProxyController {
  private readonly proxy = createProxyMiddleware({
    target: this.config.getOrThrow('GATEWAY_URI'),
    pathRewrite: { '^/bff/api': '/api' },
    on: {
      proxyReq: (proxyReq, req: any) => {
        const accessToken = req.session?.tokens?.access_token;
        if (accessToken) proxyReq.setHeader('Authorization', `Bearer ${accessToken}`);
      },
    },
  });

  constructor(private readonly config: ConfigService) {}

  @All('*')
  forward(@Req() req: Request, @Res() res: Response) {
    this.proxy(req, res, () => undefined);
  }
}
```

```typescript
// backoffice/apps/bff/src/gateway-proxy/gateway-proxy.module.ts
import { Module } from '@nestjs/common';
import { ConfigModule } from '@nestjs/config';
import { GatewayProxyController } from './gateway-proxy.controller';

@Module({
  imports: [ConfigModule],
  controllers: [GatewayProxyController],
})
export class GatewayProxyModule {}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `npx nx test bff`
Expected: PASS. (`SessionAuthGuard`, overridden in the test, runs as part of Nest's guard pipeline for the `GatewayProxyController` route and returns `false` → Nest responds `403 Forbidden` without ever invoking the proxy.)

- [ ] **Step 6: Register `GatewayProxyModule` in `AppModule` and commit**

```bash
git add backoffice/apps/bff/src/gateway-proxy backoffice/apps/bff/src/app/app.module.ts backoffice/package.json backoffice/package-lock.json
git commit -m "feat(bff): add authenticated proxy from /bff/api to the Gateway"
```

---

## Task 11: Shell — session-aware header (login/logout, JWT status)

**Files:**
- Create: `backoffice/apps/shell/src/app/session/session.service.ts`
- Create: `backoffice/apps/shell/src/app/session/session.service.spec.ts`
- Modify: `backoffice/apps/shell/src/app/app.component.ts` (or a dedicated `header.component.ts`)

**Interfaces:**
- Consumes: `GET /auth/session` from Task 9.
- Produces: `SessionService.session` signal exposing `{ authenticated, tenantId?, expiresAt? }` for the Shell header to render, and `SessionService.login()`/`logout()` navigating to `/auth/login` / `/auth/logout`.

- [ ] **Step 1: Write the failing test**

```typescript
// backoffice/apps/shell/src/app/session/session.service.spec.ts
import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { SessionService } from './session.service';

describe('SessionService', () => {
  let service: SessionService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [SessionService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(SessionService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  it('loads the session status from the BFF and exposes tenantId', () => {
    service.load();

    const req = httpMock.expectOne('/auth/session');
    req.flush({ authenticated: true, tenantId: 'tenant-a', expiresAt: 1893456000 });

    expect(service.session()).toEqual({ authenticated: true, tenantId: 'tenant-a', expiresAt: 1893456000 });
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `npx nx test shell`
Expected: FAIL — `SessionService` does not exist.

- [ ] **Step 3: Implement `SessionService`**

```typescript
// backoffice/apps/shell/src/app/session/session.service.ts
import { Injectable, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';

export interface SessionStatus {
  authenticated: boolean;
  tenantId?: string;
  expiresAt?: number;
}

@Injectable({ providedIn: 'root' })
export class SessionService {
  readonly session = signal<SessionStatus>({ authenticated: false });

  constructor(private readonly http: HttpClient) {}

  load(): void {
    this.http.get<SessionStatus>('/auth/session').subscribe((status) => this.session.set(status));
  }

  login(): void {
    window.location.href = '/auth/login';
  }

  logout(): void {
    window.location.href = '/auth/logout';
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `npx nx test shell`
Expected: PASS.

- [ ] **Step 5: Wire the header to `SessionService`**

```typescript
// backoffice/apps/shell/src/app/app.component.ts (relevant excerpt)
import { Component, OnInit, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { SessionService } from './session/session.service';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet],
  template: `
    <header>
      @if (session.session().authenticated) {
        <span>JWT válido · tenant {{ session.session().tenantId }}</span>
        <button (click)="session.logout()">Cerrar sesión</button>
      } @else {
        <button (click)="session.login()">Iniciar sesión</button>
      }
    </header>
    <router-outlet />
  `,
})
export class AppComponent implements OnInit {
  readonly session = inject(SessionService);

  ngOnInit(): void {
    this.session.load();
  }
}
```

- [ ] **Step 6: Commit**

```bash
git add backoffice/apps/shell/src/app/session backoffice/apps/shell/src/app/app.component.ts
git commit -m "feat(shell): add session-aware header wired to the BFF"
```

---

## Task 12: BFF serves the built Shell (single origin)

**Files:**
- Modify: `backoffice/apps/bff/src/app/app.module.ts`
- Modify: `backoffice/apps/bff/project.json` (build depends on `shell` and `integration-mfe` builds)

**Interfaces:**
- Consumes: static build output at `backoffice/dist/apps/shell` and `backoffice/dist/apps/integration-mfe`.
- Produces: `GET /` and any non-`/auth`, non-`/bff` path serving the Shell's `index.html`, on the same origin/port as `/auth/**` and `/bff/api/**`.

- [ ] **Step 1: Install the static file module**

Run: `cd backoffice && npm install @nestjs/serve-static`

- [ ] **Step 2: Register `ServeStaticModule` in `AppModule`**

```typescript
// backoffice/apps/bff/src/app/app.module.ts (relevant excerpt)
import { Module } from '@nestjs/common';
import { ConfigModule } from '@nestjs/config';
import { ServeStaticModule } from '@nestjs/serve-static';
import { join } from 'path';
import { AuthModule } from '../auth/auth.module';
import { GatewayProxyModule } from '../gateway-proxy/gateway-proxy.module';

@Module({
  imports: [
    ConfigModule.forRoot({ isGlobal: true }),
    ServeStaticModule.forRoot({
      rootPath: join(__dirname, 'shell-static'),
      exclude: ['/auth/{*splat}', '/bff/{*splat}'],
    }),
    AuthModule,
    GatewayProxyModule,
  ],
})
export class AppModule {}
```

- [ ] **Step 3: Make the BFF build copy the Shell's static output into `shell-static`**

Add a `postbuild` target to `backoffice/apps/bff/project.json`'s `build` executor `dependsOn` list (`["^build"]` already builds `shell`/`integration-mfe` if declared as implicit dependencies) plus a copy step:

```json
"build": {
  "executor": "@nx/webpack:webpack",
  "dependsOn": ["^build"],
  "options": {
    "assets": [
      { "glob": "**/*", "input": "dist/apps/shell/browser", "output": "shell-static" }
    ]
  }
}
```

- [ ] **Step 4: Verify end-to-end build**

Run: `npx nx run-many -t build -p shell,integration-mfe,bff`
Expected: PASS; `backoffice/dist/apps/bff/shell-static/index.html` exists.

- [ ] **Step 5: Manual smoke check**

Run: `node backoffice/dist/apps/bff/main.js` (with `.env` values for `KEYCLOAK_APPS_ISSUER_URI`, `BFF_OIDC_CLIENT_ID`, `BFF_OIDC_CLIENT_SECRET`, `BFF_SESSION_SECRET`, `BFF_PUBLIC_URL=http://localhost:4000`, `GATEWAY_URI=http://localhost:8081`, `REDIS_URL=redis://localhost:6379` set)
Expected: `curl http://localhost:4000/` returns the Shell's `index.html`; `curl http://localhost:4000/auth/session` returns `{"authenticated":false}`.

- [ ] **Step 6: Commit**

```bash
git add backoffice/apps/bff/src/app/app.module.ts backoffice/apps/bff/project.json backoffice/package.json backoffice/package-lock.json
git commit -m "feat(bff): serve the built Shell as a single origin with the BFF API"
```

---

## Task 13: Deployment — `backoffice-bff` service in Compose

**Files:**
- Create: `backoffice/Dockerfile`
- Modify: `compose.yaml`
- Modify: `.env.example`

**Interfaces:**
- Consumes: `middleware` service (Gateway) as `GATEWAY_URI=http://integration-middleware:8081`; `redis` service as `REDIS_URL=redis://integration-redis:6379`.

- [ ] **Step 1: Write the multi-stage Dockerfile**

```dockerfile
# backoffice/Dockerfile
FROM node:22-slim AS build
WORKDIR /workspace
COPY backoffice/package.json backoffice/package-lock.json ./
RUN npm ci
COPY backoffice/ ./
RUN npx nx run-many -t build -p shell,integration-mfe,bff

FROM node:22-slim AS runtime
WORKDIR /app
COPY --from=build /workspace/dist/apps/bff ./
COPY --from=build /workspace/node_modules ./node_modules
EXPOSE 4000
CMD ["node", "main.js"]
```

- [ ] **Step 2: Add the service to `compose.yaml`**

Insert after the `middleware` service block:

```yaml
  backoffice-bff:
    build:
      context: .
      dockerfile: backoffice/Dockerfile
    container_name: integration-backoffice-bff
    hostname: integration-backoffice-bff
    environment:
      NODE_ENV: production
      PORT: 4000
      GATEWAY_URI: http://integration-middleware:8081
      REDIS_URL: redis://integration-redis:6379
      KEYCLOAK_APPS_ISSUER_URI: ${KEYCLOAK_APPS_ISSUER_URI:-https://oauth2.qa.comsatel.com.pe/realms/Apps}
      BFF_OIDC_CLIENT_ID: ${BFF_OIDC_CLIENT_ID:-}
      BFF_OIDC_CLIENT_SECRET: ${BFF_OIDC_CLIENT_SECRET:-}
      BFF_SESSION_SECRET: ${BFF_SESSION_SECRET:-}
      BFF_PUBLIC_URL: ${BFF_PUBLIC_URL:-http://localhost:4000}
    ports:
      - "${BACKOFFICE_PORT:-4000}:4000"
    depends_on:
      middleware:
        condition: service_healthy
      redis:
        condition: service_healthy
    networks:
      integration-internal:
        aliases:
          - integration-backoffice-bff
          - backoffice-bff
      gateway-public:
        aliases:
          - integration-backoffice-bff
          - backoffice-bff
    healthcheck:
      test: ["CMD-SHELL", "curl -fsS http://localhost:4000/auth/session >/dev/null"]
      interval: 10s
      timeout: 5s
      retries: 12
      start_period: 30s
```

- [ ] **Step 3: Document the new variables in `.env.example`**

Append (no real secrets, matching the existing pattern for `KEYCLOAK_ISSUER_URI`):

```dotenv
# Backoffice (BFF)
KEYCLOAK_APPS_ISSUER_URI=
BFF_OIDC_CLIENT_ID=
BFF_OIDC_CLIENT_SECRET=
BFF_SESSION_SECRET=
BFF_PUBLIC_URL=http://localhost:4000
BACKOFFICE_PORT=4000
```

- [ ] **Step 4: Verify Compose config renders without secrets**

Run: `docker compose config --quiet`
Expected: exits 0.

- [ ] **Step 5: Verify the stack starts healthy**

Run: `docker compose up -d --build mysql redis kafka vault vault-init app middleware backoffice-bff`
Expected: `backoffice-bff` reaches `healthy` alongside the existing services.

- [ ] **Step 6: Commit**

```bash
git add backoffice/Dockerfile compose.yaml .env.example
git commit -m "feat(deploy): add backoffice-bff service to compose.yaml"
```

---

## Self-Review Notes

- **Spec coverage:** ADR-0001 (Native Federation) → Tasks 4-5; ADR-0002 (BFF session pattern) → Tasks 6-9; ADR-0003 (Gateway multi-issuer) → Tasks 1-2; ADR-0004 (fixed tenant) → Task 11 renders `tenantId` read-only, no switcher wired; ADR-0005 (Nx monorepo) → Task 3; ADR-0006 (BFF as independent public ingress, single-origin refinement) → Tasks 10, 12, 13; ADR-0007 (NestJS) → Tasks 3, 6-10.
- **Explicitly out of scope for this plan** (belongs to the follow-up MicroUI screens plan): Dashboard, Integration Profiles CRUD/wizard, Mapping & Transformation dry-run, Policies, Message Monitor/DLQ actions, Connectors catalog, Credentials — `integration-mfe` stays a one-screen stub throughout this plan.
- **Type consistency checked:** `SessionStatus` (Shell, Task 11) matches the `/auth/session` response shape produced in Task 9; `MicroUiRouteManifest` (Task 4) fields match the literal object passed in Task 5 Step 4; `req.session.tokens` shape is written once in Task 8 and read identically in Tasks 9 and 10.
