# Integration plan

## Surface: `scala-cli export --lockfile`

Reusing the existing export pipeline (the same one that today produces
`export.json` from `BuildInfo`) is the cleanest entry point. New flag on the
existing `export` subcommand:

```
scala-cli export --lockfile [path]            # default: project.scala.lock
scala-cli export --json --lockfile            # both at once
```

`--lockfile` runs the build's BuildOptions resolution to completion, captures
every Coursier `Fetch.Result` along the way (via the bucket-aware fetcher
described below), serializes to JSON, writes to `path`. No `--json`-specific
shape is involved; lockfile is its own document.

Symmetric read flag — applies to *every* command that runs a build:

```
scala-cli run     --lockfile [path] [--frozen]
scala-cli compile --lockfile [path] [--frozen]
scala-cli test    --lockfile [path] [--frozen]
scala-cli package --lockfile [path] [--frozen]
```

`--lockfile` without `--frozen`: prefer the lockfile, fall back to live
resolution if a bucket is missing/stale; rewrite on success.
`--lockfile --frozen`: missing/stale bucket is an error. CI uses this.

If a `project.scala.lock` exists next to `project.scala`, scala-cli auto-uses
it (no flag needed) but is non-frozen. CI sets `--frozen`. This mirrors
npm/cargo/pnpm.

## Replay strategy (verified — see `coursier-replay-verified.md`)

We do **not** need a synthetic `Repository`. Coursier's `Resolution` exposes
`projectCache0: Map[(Module, VersionConstraint), (ArtifactSource, Project)]`
plus `nextIfNoMissing` / `isDone` / `subset0`. The replay path:

1. Build synthetic `Project`s from lockfile nodes (parent = None,
   dependencyManagement = Seq.empty — BOM/parent effects are already baked
   into the pinned direct-dep versions).
2. `Resolution().withRootDependencies(...).withForceVersions0(...).withProjectCache0(synthetic)`
3. Loop `nextIfNoMissing` until `isDone` (one or two iterations — no work
   to do because the cache is complete).
4. Hand the resolved `Resolution` to
   `coursier.Artifacts().withResolution(res).runResult()` — same call
   pattern as `Artifacts.scala:471-490` already uses for the runtime
   classpath fork.

The only new type is a small `LockfileArtifactSource extends
coursier.core.ArtifactSource`, returning `(Publication, Artifact)` tuples
whose URLs and checksums come from the lockfile node's `artifacts` field.

Artifact bytes are surfaced to Coursier by **pre-warming its `FileCache`
directory**: download + sha256-verify each URL, place at
`~/.cache/coursier/v1/<host>/<path>`. `FileCache` then sees the local
files and skips the network. No new cache format, and tools that share
this cache (e.g. user runs `cs fetch` later) benefit too.

Confirmed by bytecode inspection of `coursier-core_2.13:2.1.25-M24`:
`subset0` calls `copyWithCache(...)` so the runtime classpath fork
preserves `projectCache0` after subsetting; `nextIfNoMissing` reaches
fixpoint when `missingFromCache` is empty, which is the case once the
synthetic projects are loaded. See `coursier-replay-verified.md` for the
full trace.

## Fetcher refactor

`Artifacts.fetcher` (modules/options/.../Artifacts.scala:746) currently
constructs a `Fetch` with a fresh `coursier.Fetch()` each time. The
bucket-aware version takes one extra arg:

```scala
private def fetcher(
  ...,
  bucketKey: BucketKey,
  lockfileMode: LockfileMode  // Off | Read(LockfileRepository) | Record(recorder)
): coursier.Fetch[Task] = {
  lockfileMode match
    case LockfileMode.Off          => liveFetcher(...)
    case LockfileMode.Read(repo)   =>
      liveFetcher(...)
        .withRepositories(Seq(repo.forBucket(bucketKey)))   // only this repo
        .withCache(prewarmedCache(repo, bucketKey))
    case LockfileMode.Record(rec)  =>
      liveFetcher(...).addArtifactsTransform { ... rec.observe(bucketKey, _) }
}
```

`Record` mode wraps a live fetcher; on completion it captures
`Fetch.Result.resolution` + `fullDetailedArtifacts0` into the recorder. At
the end of the build, the recorder owns every bucket's resolved graph and
serializes the lockfile.

Every existing call site that does `Artifacts.fetchAnyDependencies` or
`Artifacts.artifacts` gets a `bucketKey` parameter or a thread-local
`BucketContext` (the latter avoids touching every call site). A
`BucketContext.withBucket("tooling.scala-compiler.scala-3.7.4")(block)`
wrapper is probably nicer.

## Bucket-key derivation

Each call site declares its bucket key. Because most call sites are in a
single file (`Artifacts.scala`), this is a small change: each of the
`fetchCsDependencies` calls inside `Artifacts.apply` already has a
`cache.withMessage("Downloading <thing>")` annotation. That message string
maps 1:1 to a bucket key — the refactor is to replace the message with a
structured `BucketKey` and derive the message from it.

Concretely:
- `compilerArtifacts` → `tooling.scala-compiler.<sv>`
- `bridgeJarsOpt`     → `tooling.scala-bridge.<sv>`
- `compilerPlugins0`  → `tooling.compiler-plugins.<sv>[.<nv>]`
- `fetchedScalaJsCli` → `tooling.scalajs-cli.<jsCliVer>.<jsVer>`
- `fetchedScalaNativeCli` → `tooling.scala-native-cli.<nativeCliVer>`
- main `fetchAnyDependenciesWithResult` (line 452) →
  `user.<scope>.<platform>.<sv>[.<jsVer>][.<nVer>]`
- the runner fetch (line 539) → `tooling.runner.<sv>.<runnerVer>`

`Bloop.bloopClassPath` → `tooling.bloop.<bloopVer>`.
`Doc.scaladoc fetch` → `tooling.scaladoc.<sv>`.
`ReplArtifacts.default` → `tooling.repl.<sv>` (note: contains user deps).
`ReplArtifacts.ammonite` → `tooling.ammonite.<sv>.<ammVer>`.
`ScalafixArtifacts.fetchScalafixInterfaces` →
  `tooling.scalafix-interfaces.<scalafixVer>`.
`ScalafixArtifacts.artifacts` (cli) →
  `tooling.scalafix-cli.<derivedSv>.<scalafixVer>`.
`ScalafixArtifacts.artifacts` (rules) →
  `tooling.scalafix-rules.<sv>`.
`Package.scala:869` (python-interface) →
  `tooling.python-interface.<piVer>`.
`Package.scala:946` reuses an existing resolution → no new bucket.
`ScalaJsLinker.scala:71` → `tooling.scalajs-linker.<jsVer>` (alt path).
`FetchExternalBinary.fetch` → `binary.<tool>.<ver>.<platformSuffix>`.

## Build pipeline placement

```
parse directives
  ↓
build BuildOptions
  ↓
[lockfile read step]   ← if --lockfile present, load + validate fingerprints
  ↓
Artifacts.apply        ← fetcher uses LockfileMode = Read or Record
  ↓
[lockfile write step]  ← only in Record mode (i.e. export --lockfile or refresh)
  ↓
compile / package / test / etc.
```

Read step: parse JSON, validate `version`, validate
`fingerprints.scalaCliConstants` against the running scala-cli's
`Constants.*` (so a scala-cli upgrade that changes a default forces a
refresh in `--frozen` mode). On mismatch in non-frozen mode, log and treat
mismatched buckets as missing.

Write step: take the recorder's per-bucket graphs, sort everything,
serialize. If a lockfile already exists, only the buckets that were re-
resolved are overwritten — others are preserved. This is what makes per-
bucket invalidation real: bumping a `using dep` rewrites only `user.*`.

## Interaction with existing exports

`export --json` (this branch) emits `BuildInfo` with `runtimeDependencies`,
`compilerPlugins`, `toolingDependencies`. That set is the **roots** of each
tooling bucket. The lockfile's `roots` field for those buckets should
exactly match. Worth adding a CI test:

> for each bucket B in lockfile, B.roots == subset of equivalent fields in
> export.json

`list-targets` (also this branch) enumerates `(scope, platform)` pairs.
That product is the set of `user.*` bucket keys.

## Coursier-side considerations

- `Resolution.subset0` (used at `Artifacts.scala:473` for runtime classpath)
  works on a `Resolution` object regardless of whether its origin is live or
  synthetic, so the runtime-classpath fork still works.
- `coursierapi.Logger` reuse — the lockfile read path can short-circuit the
  download progress logger entirely, since pre-populating the cache is fast.
- Forced versions: must be replayed in `Read` mode too. Coursier's resolver
  honors `addForceVersion0`; the lockfile preserves them so a synthetic
  resolution behaves identically.
- BOM resolution: the lockfile records post-eviction versions, so the
  synthetic `Project` doesn't need `dependencyManagement`. *However*,
  recording BOM coordinates in `bucket.inputs.extra.boms` is useful so the
  fingerprint catches a BOM bump.

## Refresh UX

```
scala-cli export --lockfile                   # full refresh
scala-cli export --lockfile --refresh user.*  # refresh only user buckets
scala-cli export --lockfile --refresh-stale   # refresh only buckets whose inputs changed
```

The last is the default for non-frozen builds: inputs change → that bucket
re-resolves → lockfile updated in place. No flag needed for the common case.

## Phases (suggested rollout)

1. **Phase 1 — recording only.** Add `BucketContext`, thread bucket keys
   through every call site, add `Record` mode that captures resolutions but
   doesn't write a lockfile. Add `export --lockfile` that uses the recorder.
   At this point users can produce lockfiles but builds don't consume them.
2. **Phase 2 — replay.** Implement `LockfileRepository` and `Read` mode. Add
   `--lockfile` flag to read-side commands. Live fallback if buckets
   missing.
3. **Phase 3 — frozen.** Add `--frozen`, error on mismatch, allowlist hosts
   from recorded repositories. CI guidance docs.
4. **Phase 4 — auto-detect.** If `project.scala.lock` exists, use it without
   the flag (non-frozen). Document precedence vs `--lockfile path`.
5. **Phase 5 — partial refresh.** Per-bucket invalidation + selective
   rewrite. (Phase 1's recorder already supports this; this phase exposes
   the `--refresh` flags.)

## Out of scope (named to be explicit)

- JVM coursier-jvm index lockfile entries (separate cache).
- Cross-machine lockfiles for native binary classifiers (would need to
  record per-platform-suffix entries; the schema supports it via
  `binaries.*` but the ingest needs every target platform to have been
  exercised).
- "Vendored" mode that ships the actual JAR bytes alongside the lockfile —
  out of scope, but the `extension` + `sha256` fields are sufficient to
  retrofit if desired.
