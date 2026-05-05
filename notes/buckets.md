# Resolution bucket taxonomy

A "bucket" is one independent Coursier resolution. The lockfile stores buckets
side-by-side; bumping any input only invalidates the buckets that depend on it.

## Shape

Each bucket has:
- `key` — stable identifier (used for lookup). Encodes every input that would
  alter the resolution.
- `inputs` — the human-readable values that go into the key (recorded for
  debugging and to drive freshness checks).
- `roots` — the `coursier.Dependency` list passed to `Fetch` originally.
- `forcedVersions` — `(module, version)` pairs forced via
  `addForceVersion0`. Empty for most tooling buckets; non-empty for the user
  bucket and the JS CLI bucket.
- `repositories` — extra repositories beyond the defaults
  (sonatype-snapshots, scala3-nightlies, mvn-central-snapshots, user
  resolvers).
- `nodes` — the resolved graph. See `schema.md`.
- `intransitive` — bucket-level flag for buckets whose roots are all
  intransitive (compiler plugins, the JVM runner). Lets the replay short-
  circuit transitive walking.

## Key derivation

The key is `<group>.<name>.<input1>.<input2>...` so that a bucket's identity is
self-describing. Examples:

| bucket key                                        | invalidated when            |
|---------------------------------------------------|-----------------------------|
| `user.main.jvm.scala-3.7.4`                       | user deps, scala, repos     |
| `user.test.native.scala-3.7.4.native-0.5.7`       | + native version            |
| `tooling.scala-compiler.scala-3.7.4`              | scala version               |
| `tooling.scala-bridge.scala-3.7.4`                | scala version               |
| `tooling.scaladoc.scala-3.7.4`                    | scala version               |
| `tooling.runner.scala-3.7.4.r-1.9.0`              | scala or runner version     |
| `tooling.compiler-plugins.scala-3.7.4`            | scala version + plugin set  |
| `tooling.compiler-plugins.scala-3.7.4.native-0.5.7` | + native version (nscplugin) |
| `tooling.scalajs-cli.1.18.0.js-1.18.0`            | js-cli or js version        |
| `tooling.scalajs-linker.1.18.0`                   | js version                  |
| `tooling.scala-native-cli.0.5.7`                  | native cli version          |
| `tooling.bloop.2.0.10`                            | bloop version               |
| `tooling.repl.scala-3.7.4`                        | scala version + user deps   |
| `tooling.ammonite.scala-3.7.4.amm-3.0.2`          | scala or ammonite version   |
| `tooling.scalafix-interfaces.0.13.0`              | scalafix version            |
| `tooling.scalafix-cli.scala-2.13.16.0.13.0`       | scalafix derived sv + ver   |
| `tooling.scalafix-rules.scala-2.13.16`            | scala version + rules set   |
| `tooling.python-interface`                        | constants only              |
| `binary.scalafmt.3.7.17`                          | version + platform suffix   |

## Bucket dependencies

Some buckets logically depend on others (the lockfile doesn't enforce this,
but the UX should). For example:

- `tooling.scaladoc.<sv>` is invalidated by the same input as
  `tooling.scala-compiler.<sv>` — both are keyed on `<sv>`, so a single Scala
  bump invalidates them together. No explicit dependency edge needed.
- `tooling.scalajs-cli` carries a forced `scalajs-linker` version; it is
  effectively keyed on `(jsCliVersion, jsVersion)`. The user bucket is also
  keyed on `jsVersion`. So a JS bump invalidates both. Independent buckets,
  same trigger — fine.
- `tooling.scalafix-cli`'s key contains a `<derived-sv>` that is *read from
  inside* `scalafix-interfaces`. So the `scalafix-interfaces` bucket must be
  resolved first to compute the cli key. This is the only ordering
  constraint; the lockfile records the derived value as a **resolved input**
  alongside the requested inputs.

## What a "bucket replay" looks like

Replay = "I have the lockfile bucket and want a `Resolution` that matches it,
without going to network for POMs."

Steps:

1. Build a `Map[Module, Project]` where each entry comes from the bucket's
   `nodes` list. The synthetic `Project` records: post-eviction version,
   *direct* dependencies (already at their post-eviction versions), and
   minimal POM metadata (packaging, classifier).
2. Wrap that map in a `Repository` that returns `Right(project)` for known
   modules and `Left(NotFound)` otherwise.
3. Construct `coursier.Fetch()` with **only that repository** (no default
   resolvers, no Maven Central, nothing live), the bucket's roots as
   dependencies, and `addForceVersion0` matching the bucket. Run it.
4. The resolution succeeds (graph already pinned) and yields exactly the
   nodes from the lockfile.
5. Fetch artifacts using URL+sha from `nodes[i].artifacts`. Pre-fill the
   Coursier file cache with verified files; or stream and verify on the fly.

This is non-invasive: every existing call site keeps using `coursier.Fetch`.
The only injection point is whether the cache + repos come from "live" or
"lockfile mode."

## Resolved-input recording

Some inputs aren't user-supplied; they come from defaults that move (e.g.
`Constants.scalafixVersion`, `BloopRifleConfig.defaultVersion`,
`Constants.scalaJsVersion` when the user didn't pin one). The lockfile
records the **resolved** value of every input on bucket creation so a later
scala-cli upgrade doesn't silently invalidate buckets just because a default
changed. The freshness check compares the lockfile's resolved inputs against
the *current request's* resolved inputs, not against the defaults.

## "Frozen" mode contract

In `--frozen` mode (synonym for `--locked`):
- Every bucket the build needs must be present.
- Every node referenced for fetching must have a `sha256` (or `sha1`) and a
  URL whose host is in an allowlist (defaulting to the lockfile's recorded
  repositories).
- A missing bucket is an error, not a fallback to live resolution.
- An updated input (Scala bump, new `using dep`) is an error in frozen mode
  — the user must explicitly run `scala-cli export --lockfile` to refresh.

## Refresh granularity

Stale-detection per bucket, not per project. A `using scala 3.7.4 → 3.7.5`
bump invalidates compiler/bridge/scaladoc/repl/user buckets but leaves
Bloop, Native CLI, JS CLI, scalafmt, scalafix-interfaces alone. This is the
real win versus a flat npm-style lockfile: you don't re-resolve toolchains
on dep churn.
