# Verified: Coursier replay strategy

Coursier in use: **2.1.25-M24** (`io.get-coursier:coursier-core_2.13`).

## What I verified (by inspecting Resolution.class bytecode)

`coursier.core.Resolution` exposes the following relevant API:

```scala
// Pre-population point.
val projectCache0: Map[(Module, VersionConstraint), (ArtifactSource, Project)]

def withProjectCache0(...): Resolution
def withForceVersions0(...): Resolution
def withRootDependencies(...): Resolution

// Iteration.
def missingFromCache: Set[(Module, VersionConstraint)]
def isDone: Boolean
def nextIfNoMissing: Resolution

// Trim to a sub-graph (used by scala-cli runtime classpath fork).
def subset0(deps: Seq[Dependency]): Either[DependencyError, Resolution]
//   Implementation note: subset0 calls copyWithCache → projectCache0 is preserved.

// Artifacts (after the resolution is done).
def dependencyArtifacts0(...): Seq[(Dependency, Either[VariantPublication, Publication], Artifact)]
```

Plus `coursier.Artifacts().withResolution(res).runResult()` consumes a
ready-made `Resolution` and only fetches artifacts (no further graph walk).
scala-cli already uses this pattern at `Artifacts.scala:471-490` for the
runtime fork.

## Replay procedure (revised — simpler than original notes)

The original design proposed a synthetic `Repository` that returned
pre-pinned `Project`s on demand. Verification shows we can do something
even cleaner:

**Pre-populate `projectCache0` directly. No `Repository` needed.**

```scala
val syntheticProjects: Map[(Module, VersionConstraint), (ArtifactSource, Project)] =
  bucket.nodes.map { node =>
    val key = (node.module, VersionConstraint(node.version))
    val proj = Project(
      module = node.module,
      version = node.version,
      dependencies = node.dependencies.map { d =>
        Configuration.compile -> Dependency(d.module, d.version)
      },
      configurations = Map.empty,
      parent = None,                    // BOM/parent effects already baked in
      dependencyManagement = Seq.empty, // post-eviction versions are pinned on the deps directly
      properties = Seq.empty,
      profiles = Seq.empty,
      versions = None,
      snapshotVersioning = None,
      packagingOpt = node.pom.packaging.map(Type(_)),
      relocated = false,
      actualVersionOpt = None,
      publications = Seq.empty,
      info = Info.empty
    )
    key -> (LockfileArtifactSource(node) -> proj)
  }.toMap

val resolution = Resolution()
  .withRootDependencies(bucket.roots.map(_.toCoursierDependency))
  .withForceVersions0(bucket.forcedVersions.toMap)
  .withProjectCache0(syntheticProjects)

@tailrec def loop(r: Resolution): Resolution =
  if r.isDone then r else loop(r.nextIfNoMissing)
val resolved = loop(resolution)

// Now resolved is identical (in projectCache and graph shape) to a live one.
// Hand to coursier.Artifacts to fetch jars by URL+sha.
val files = coursier.Artifacts()
  .withResolution(resolved)
  .withCache(prewarmedCache)        // contents pre-filled from lockfile URLs+shas
  .runResult()
```

## Why this works

- `nextIfNoMissing` only consults `projectCache0`. If a key is missing it
  short-circuits without calling a `Repository`. We pre-fill every key the
  resolver will request, so `missingFromCache` is empty after one
  iteration and `isDone` flips true.
- Coursier's `dependencyManagementRequirements0(project)` checks
  `project.parent0`. By giving every synthetic project `parent = None`, the
  resolver never asks for a parent POM. Same for BOM dependencies (we set
  `bomDependencies = Seq.empty` and don't include `BomDependency` entries
  in the resolution).
- BOM-driven version pins live in the *direct deps* of each synthetic
  project (post-eviction), so the resolver's reconciliation step sees no
  conflicts and produces exactly the lockfile graph.
- `subset0` calls `copyWithCache(...)` (verified in bytecode: method name
  literally `copyWithCache`) which preserves `projectCache0`. So
  `Artifacts.scala:473`'s runtime-classpath fork (`subset0` → produce
  smaller resolution → fetch its artifacts) keeps working under replay
  without any new code path.

## Implications for the integration plan

1. **Drop `LockfileRepository` from the design.** No new
   `coursier.core.Repository` impl. The replay path goes through a
   pre-built `Resolution` and `coursier.Artifacts.withResolution`.

2. **One small new piece: `LockfileArtifactSource`.** This is a
   `coursier.core.ArtifactSource` (the supertype `Repository` extends). It
   only needs `def artifacts(dep, project, classifiers)` to return a
   `Seq[(Publication, Artifact)]` whose URLs and checksums come from the
   lockfile. This is what `dependencyArtifacts0` calls when assembling the
   list of files to fetch.

3. **Refactor at `Artifacts.scala`:** the call sites that today do
   `fetchCsDependencies(...)` need to branch on a "lockfile mode" flag. In
   replay mode they construct the `Resolution` from cache + roots, drive
   `nextIfNoMissing` to fixpoint, then call `coursier.Artifacts()` exactly
   as the existing runtime fork does. No new abstraction needed beyond a
   `LockfileBucket → Resolution` constructor and the `ArtifactSource`.

4. **Cache pre-warming:** the `prewarmedCache` step writes verified bytes
   to Coursier's `FileCache` directory before the artifact fetch runs.
   Coursier's existing `FileCache` then sees the local files and skips
   network. Alternatively, a stricter mode short-circuits the cache
   entirely and reads directly from a recorded path — but reusing
   `FileCache` is less invasive.

## Risks / things still to verify before implementing

- **`forceVersions0` semantics during replay.** The lockfile records
  forced versions; when we set them on the replayed `Resolution`, the
  reconciliation phase may try to overwrite something. Need a quick test
  that the resolution loop is a no-op when `projectCache0` already
  contains the forced version. Plan: small Scala test that builds a
  lockfile-style Resolution with one forced version and confirms
  `isDone == true` after `nextIfNoMissing`.
- **`mapDependencies` (used for forceScalaVersion).** scala-cli's
  `Artifacts.fetcher` uses `addForceVersion0` rather than
  `mapDependencies`, so we should be fine. Worth confirming.
- **`Variant` configurations** (Coursier 2.1.25's variant-aware
  resolution). The bytecode shows `dependencies0: Seq[(Variant,
  Dependency)]`. For the synthetic project the simple
  `Configuration.compile` mapping should work; if not, set
  `defaultVariantAttributes = VariantSelector.AttributesBased.empty`.
- **Snapshot resolution.** SNAPSHOT artifacts go through Coursier's
  `SnapshotVersioning` path; pre-pinning the resolved snapshot version in
  `node.version` (with the timestamped form) may be necessary. Worth a
  test against a snapshot dep before phase 1 ships.

## Net effect on the design notes

`notes/integration.md` should be updated to:
- Remove the `LockfileRepository` paragraph.
- Replace with the `projectCache0` preload approach.
- Keep the `BucketContext` / `bucketKey` threading; that is unchanged.
- Keep the rollout phases.
