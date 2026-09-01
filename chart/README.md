# systemintegrator chart

Helm chart for four of the services defined in the repo's
`docker-compose.yaml`: `app`, `middleware`, `backoffice-bff` and
`backoffice-shell`. It does not deploy MySQL, Redis, Kafka or Vault --
point `global.*` in `values.yaml` at wherever those already run.

## Install

```bash
helm install myrelease ./chart \
  --set bff.oidc.clientSecret=<keycloak-client-secret> \
  --set bff.publicUrl=https://backoffice.example.com \
  --set global.mysql.host=mysql.example.svc \
  --set global.redis.host=redis.example.svc \
  --set global.kafka.bootstrapServers=kafka.example.svc:9092 \
  --set global.vault.addr=http://vault.example.svc:8200
```

## Notes

- `bff`'s Kubernetes Service is intentionally named `backoffice-bff`
  (not release-scoped) because `backoffice-shell`'s nginx config has
  `proxy_pass http://backoffice-bff:4000` baked into its image. If you
  install more than one release of this chart into the same namespace,
  only one can own that Service name.
- `bff.gatewayUri` auto-derives to the `middleware` Service's in-cluster
  DNS name when left empty; set it explicitly if middleware lives
  elsewhere (a different release, another namespace, etc).
- Prefer `bff.oidc.existingSecret` (with `existingSecretClientSecretKey`
  / `existingSecretSessionSecretKey`) over the plaintext
  `bff.oidc.clientSecret` / `bff.sessionSecret` values outside local/dev.
- Set `ingress.enabled=true` and `ingress.<component>.host` to expose
  `shell` / `bff` / `middleware` externally; otherwise use
  `kubectl port-forward` or your own Ingress/Service in front of them.

## Validate

```bash
helm lint ./chart
helm template myrelease ./chart
```
