# Task 4 verification report

Worktree: `task5-keycloak-gateway`
Date: 2026-08-15 (America/Lima)

## Status summary

| Verification | Result |
| --- | --- |
| Gateway Maven tests | Passed: 7 tests, 0 failures, 0 errors, 0 skipped. |
| Testcontainers test-profile subset | Inconclusive: not rerun during final verification. |
| Compose configuration | Passed: `docker compose config --quiet` exited 0 after the listener-variable change. |
| `app` / `middleware` Docker build | Passed: both images built. |
| Kafka-only startup | Passed after the image-tag correction: bounded startup exited 0 and Kafka became healthy after 12 seconds. |
| Full stack boot after tag fix | Blocked: bounded `docker compose up --wait` could not access the Docker API. |
| Stack cleanup | Passed: `docker compose down` exited 0. |

## Completed commands and exact results

### Gateway Maven suite

```text
mvn -f gateway/pom.xml test
```

The first sandboxed run could not resolve Maven Central because socket access was denied. A permitted retry completed with:

```text
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: 17.610 s
```

The only additional output was Mockito/Byte Buddy dynamic Java-agent deprecation warnings and hostname detection warnings.

### Required Testcontainers subset

```text
mvn -q "-Dapi.version=1.40" "-Dspring.profiles.active=test" "-Dtest=IntegrationProfileEndToEndTest,IntegrationProfilePersistenceAdapterTest" test
```

Result: inconclusive for final verification because the subset was not rerun after the Compose tag/listener fixes. The earlier bounded attempt timed out after 64.053 seconds (exit 124) without captured test output; that result does not establish a pass or source test failure.

### Compose configuration

```text
docker compose config --quiet
```

Result: exit 0. Docker emitted this local permission warning:

```text
WARNING: Error loading config file: open C:\Users\ianache\.docker\config.json: Access is denied.
```

Service enumeration also exited 0:

```text
docker compose config --services
kafka
mysql
redis
app
middleware
```

No unresolved-variable error occurred.

### Required Docker build

```text
docker compose build app middleware
```

Result: exit 0. The final output was:

```text
Image task5-keycloak-gateway-app Built
Image task5-keycloak-gateway-middleware Built
```

BuildKit exported `task5-keycloak-gateway-app:latest` and `task5-keycloak-gateway-middleware:latest` successfully.

### Kafka-only startup after image-tag correction

The Compose Kafka service uses the official `apache/kafka:3.8.1` image. The previously recorded bounded Kafka-only verification passed:

```text
docker compose config --quiet: exit 0
Kafka-only bounded startup: exit 0; healthy after 12 seconds
Kafka listeners: internal 9092 and external 29092 confirmed
Temporary Kafka resources: removed successfully
```

### Full-stack boot after tag and listener fixes

```text
docker compose up --wait
```

Result: blocked before startup because the Docker API was inaccessible in the execution environment:

```text
WARNING: Error loading config file: open C:\Users\ianache\.docker\config.json: Access is denied.
unable to get image 'apache/kafka:3.8.1': permission denied while trying to connect to the Docker API at npipe:////./pipe/docker_engine
```

The command was bounded to 45 seconds and exited 1 after approximately 1.3 seconds; no full-stack readiness result was obtained. The `apache/kafka:3.8.1` tag was therefore not reported as a full-stack pass.

### Historical pre-fix stack boot

```text
docker compose up -d --build mysql redis kafka app middleware
```

Result: exit 1. Exact blocker:

```text
Image bitnami/kafka:3.9 Error failed to resolve reference "docker.io/bitnami/kafka:3.9": docker.io/bitnami/kafka:3.9: not found
Error response from daemon: failed to resolve reference "docker.io/bitnami/kafka:3.9": docker.io/bitnami/kafka:3.9: not found
```

Follow-up inspection:

```text
docker compose ps
NAME      IMAGE     COMMAND   SERVICE   CREATED   STATUS    PORTS
```

`docker compose logs --no-color middleware` exited 0 with no output. No services had been created.

```text
docker compose down
```

Result: exit 0.

### Repository checks before documentation update

```text
git diff --check
git status --short
```

Result: exit 0, no diff-check output, and no status entries. Git printed an unrelated warning twice because `C:\Users\ianache/.config/git/ignore` is inaccessible.

Recent existing commits at that point:

```text
e84244e fix: align gateway compose profile documentation
6644d08 feat: add compose app and keycloak middleware
13d6b4c fix: scope gateway issuer to qa profile
ca67fd9 feat: secure gateway with keycloak tenant propagation
3991f5a fix: expose keycloak issuer configuration
```

## Documentation action

The README was updated with one troubleshooting note: the verified unavailable Kafka image tag blocks stack startup, and a successful app/middleware image build alone is not full-stack verification. No source code, Compose configuration, Keycloak credentials, or secrets were changed.

The README-only change was committed:

```text
5fadff3 docs: note unavailable Kafka image tag
```

Final repository verification after that commit:

```text
git diff --check
git status --short
```

Result: exit 0 with no diff-check output and no status entries. The only output beyond the commit line was the pre-existing inaccessible-global-ignore warning.

## Blockers and concerns

1. The Testcontainers subset needs a separately provisioned run with sufficient time and log capture; this bounded run does not determine its outcome.
2. Historical pre-fix verification was blocked by the unavailable `bitnami/kafka:3.9` image; the Compose reference is now `apache/kafka:3.8.1`. Current full-stack verification is separately blocked by Docker API permissions.
3. Docker runs despite its access-denied user configuration warning; local Docker-config permissions should be repaired for reliable tooling.

## Final-verification conclusion

Configuration verification passes, including the current `KAFKA_ADVERTISED_LISTENERS` value using `localhost:${KAFKA_PORT:-29092}`. Kafka-only startup passed with `apache/kafka:3.8.1`. Full-stack verification was attempted after the tag fix with bounded `docker compose up --wait`, but was blocked by Docker API permissions. Testcontainers remains inconclusive because it was not rerun.
