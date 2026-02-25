# Redis Coordinator Migration Notes

## Scope

HyQuery now supports two network coordinator modes:
- `udp` (default, existing behavior)
- `redis` (new)

`network.role` semantics are unchanged:
- `primary` serves network-wide responses
- `worker` publishes only

## Safe Defaults

- Existing configs migrate safely with implicit `network.coordinator = "udp"`.
- Redis staleness default is `network.staleAfterSeconds = 10`.
- Redis availability is hard-fail in v1 (startup and runtime fail-closed behavior).

## Redis v1 Constraints

- Single Redis instance only (no Sentinel/Cluster support yet).
- Security support includes TLS and Redis ACL (`username` / `password`).
- Trust model relies on Redis ACL/TLS only in Redis mode (no worker signing layer).

## Recommended Rollout

1. Keep existing `udp` mode unchanged while validating new version.
2. Provision Redis with ACL + TLS.
3. Migrate workers first (`network.coordinator = "redis"`, unique `network.id`).
4. Migrate primaries to Redis coordinator and verify aggregated totals.
5. Validate namespace scoping and optional `includeGlobalNamespace` behavior.

## Operational Behavior

- Redis startup health check failure: plugin startup fails in Redis mode.
- Redis runtime read/publish failure: hard-fail path is enforced; no silent network-to-local fallback.
- UDP mode behavior remains unchanged, including HMAC verification for worker updates.
