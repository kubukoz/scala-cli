# scala-cli lockfile design notes

Working notes for a lockfile system that pins both user dependencies *and*
the toolchain (Scala compiler, scaladoc, Bloop, scala-native-cli,
scalajs-cli, scalajs-linker, scalafix, ammonite, runner, native runtime,
external binaries).

Read in this order:

1. **`call-sites.md`** — every Coursier resolution scala-cli runs today,
   keyed to file:line. The lockfile must replay all of these.
2. **`buckets.md`** — taxonomy: each independent resolution becomes a
   "bucket" with its own key, inputs, and refresh trigger. Per-bucket
   invalidation is the headline feature.
3. **`schema.md`** — concrete JSON layout. Top-level inputs, repositories,
   fingerprints, per-bucket `roots`/`forcedVersions`/`nodes`. Determinism
   rules for diff-friendliness.
4. **`example.lock.json`** — small but realistic sample showing user +
   tooling buckets.
5. **`integration.md`** — surface (`scala-cli export --lockfile`,
   `--frozen`), replay strategy via `Resolution.projectCache0`, where to
   thread bucket keys through `Artifacts.scala`, rollout phases,
   interaction with `export --json` and `list-targets` from this branch.
6. **`coursier-replay-verified.md`** — bytecode-level verification that
   pre-populating `Resolution.projectCache0` is sufficient for replay,
   removing the need for a synthetic `Repository`. Lists three remaining
   risks worth a smoke test before phase 1 ships (forced-version
   reconciliation, variant configurations, snapshot pinning).

## Headline ideas

- **Buckets, not a flat lockfile.** Each Coursier resolution gets its own
  bucket so a `using dep` change re-resolves only user buckets, a Scala
  bump re-resolves user + scala-compiler/bridge/scaladoc/repl, a Native
  bump re-resolves only Native-related buckets.
- **`scala-cli export --lockfile`** is the producer surface, parallel to
  `--json`. CI consumes it via `--lockfile --frozen`.
- **Synthetic Repository for replay.** No invasive surgery in Coursier;
  the lockfile registers a `Repository` that hands back synthetic
  `Project`s built from pinned nodes, so existing `Fetch` call sites are
  reused as-is.
- **Tooling coverage matches `export --json`.** The branch already exports
  `runtimeDependencies` / `compilerPlugins` / `toolingDependencies` for
  Native; those are exactly the bucket roots, and the lockfile is a
  superset of that information.
