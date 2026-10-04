# Server config commands

The restored server command tree is `/simpleclouds config server` and
`/simpleclouds config common`. Both reading and writing require gamemaster/operator
permission (level 2); these are not client-local configuration commands.

Examples:

```text
/simpleclouds config server get cloudMode
/simpleclouds config server set cloudMode AMBIENT
/simpleclouds config server set cloudMode default
/simpleclouds config common get weather.lightning_and_thunder.lightningSpawnIntervalMinimum
```

Tab completion offers known paths and enum/boolean values. String values may contain
spaces. Integer, double, boolean, string and enum options retain the original
config-spec validation. Lists and longs remain readable but have no set operation,
matching the original builder's supported types. Unknown paths, invalid values and
unprivileged requests cannot change configuration.

Changes publish `OnConfigOptionSaved`, validate any override, and save atomically.
A failed save restores the old in-memory value and reports failure. A changed
restart-required option returns result 2 and a restart notice; ordinary changes
return 1, unchanged values 0. `get` preserves the original typed command result.

Vanilla string wire arguments replace the old Forge-only argument serializers;
the option's actual spec remains the authority for its value type. The existing
client config builder is not loaded by this new server command implementation.

SERVER commands save to `<world>/serverconfig/simpleclouds-server.toml`. Existing
world settings are never replaced by templates. New world configurations seed from
`defaultconfigs/simpleclouds-server.toml` when present, otherwise from the old global
`config/simpleclouds-server.toml` as a one-time migration. That legacy file is not
moved or deleted. COMMON stays global. Stopping the server releases the world
binding and clears its value caches before another world is opened.

Dedicated-server classloading, two-client synchronization and complete reload/sleep
behavior remain C07 acceptance work; see the master plan for exact evidence/limits.
