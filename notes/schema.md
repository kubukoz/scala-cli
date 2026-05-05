# Lockfile schema

JSON. Stable key order. One file per project, default location
`./project.scala.lock` (next to `project.scala`); overridable via
`--lockfile <path>`.

## Top level

```jsonc
{
  "version": 1,                          // schema version
  "scalaCliVersion": "1.10.0",           // tool that wrote this lockfile
  "generated": "2026-05-05T12:34:56Z",   // for diagnostics; not part of validity
  "inputs": {
    // The inputs that determine bucket identities. Stored once at the top
    // level for buckets that share them, and per-bucket otherwise.
    "scalaVersions": ["3.7.4"],
    "platforms":     ["jvm", "native"],
    "scalaJsVersion":     null,
    "scalaNativeVersion": "0.5.7",
    "scalaJsCliVersion":  null,
    "scalaNativeCliVersion": "0.5.7",
    "bloopVersion":       "2.0.10",
    "scalafixVersion":    "0.13.0",
    "ammoniteVersion":    null,
    "javaVersion":        21
  },
  "repositories": [
    // Recorded so a frozen replay can validate that artifact URLs come from
    // an allow-listed host. Empty list = whatever Coursier defaults to,
    // which means we ALSO record those defaults to be deterministic.
    {"id": "central",      "url": "https://repo1.maven.org/maven2"},
    {"id": "sonatype-s01", "url": "https://s01.oss.sonatype.org/content/repositories/releases"}
  ],
  "fingerprints": {
    // Per-bucket-source fingerprints, used to detect what changed.
    "userDirectives":    "sha256:…",   // hash of `using dep`, `using scala`, etc.
    "buildOptions":      "sha256:…",   // hash of resolved BuildOptions sans transients
    "scalaCliConstants": "sha256:…"    // hash of relevant Constants.* values
  },
  "buckets": {
    "user.main.jvm.scala-3.7.4": { … },
    "user.main.native.scala-3.7.4.native-0.5.7": { … },
    "tooling.scala-compiler.scala-3.7.4": { … },
    "tooling.scala-bridge.scala-3.7.4": { … },
    "tooling.scaladoc.scala-3.7.4": { … },
    "tooling.runner.scala-3.7.4.r-1.9.0": { … },
    "tooling.compiler-plugins.scala-3.7.4.native-0.5.7": { … },
    "tooling.scala-native-cli.0.5.7": { … },
    "tooling.bloop.2.0.10": { … }
  },
  "binaries": {
    "binary.scalafmt.3.7.17.x86_64-pc-linux": { … },
    "binary.pgp.0.2.4.x86_64-pc-linux":       { … }
  }
}
```

## Bucket entry

```jsonc
{
  "kind": "user" | "tooling",
  "scope": "main" | "test" | null,           // null for tooling
  "platform": "jvm" | "js" | "native" | null, // null where irrelevant (e.g. scalafix-interfaces)
  "intransitive": false,                     // true => roots are intransitive

  "inputs": {                                // resolved values (not requested)
    "scalaVersion":       "3.7.4",
    "scalaBinaryVersion": "3",
    "scalaJsVersion":     null,
    "scalaNativeVersion": "0.5.7",
    "extra": {                               // bucket-specific
      "runnerVersion": "1.9.0"
    }
  },

  "extraRepositories": [],                   // additions beyond top-level repos

  "roots": [                                 // exactly what gets passed to Fetch
    {
      "module": "org.scala-lang::scala3-compiler",
      "version": "3.7.4",
      "intransitive": false,
      "exclusions": [],
      "configuration": "default(compile)",
      "attributes": { "type": "jar" },
      "userParams": []                       // mirrors AnyDependency.userParams
    }
  ],

  "forcedVersions": [
    {"module": "org.scala-js:scalajs-linker_2.13", "version": "1.18.0"}
  ],

  // The fully-resolved post-eviction graph. Each node lists its DIRECT
  // children (post-eviction). Walking the graph from `roots` gives the
  // classpath.
  "nodes": [
    {
      "module": "org.scala-lang:scala3-compiler_3",
      "version": "3.7.4",
      "configuration": "default",
      "dependencies": [
        {"module": "org.scala-lang:scala3-library_3", "version": "3.7.4"},
        {"module": "org.scala-lang:tasty-core_3",     "version": "3.7.4"},
        {"module": "org.scala-lang:scala3-interfaces", "version": "3.7.4"},
        {"module": "org.scala-lang:scala-library",    "version": "2.13.15"},
        {"module": "org.jline:jline-reader",          "version": "3.27.1"}
      ],
      "artifacts": [
        {
          "classifier": "",
          "extension":  "jar",
          "url":        "https://repo1.maven.org/maven2/org/scala-lang/scala3-compiler_3/3.7.4/scala3-compiler_3-3.7.4.jar",
          "sha256":     "abc123…",
          "size":       12345678
        },
        {
          "classifier": "sources",
          "extension":  "jar",
          "url":        "https://repo1.maven.org/maven2/org/scala-lang/scala3-compiler_3/3.7.4/scala3-compiler_3-3.7.4-sources.jar",
          "sha256":     "def456…",
          "size":       4567890,
          "optional":   true                  // sources are not required to build
        }
      ],
      "pom": {                                // metadata needed to reconstruct Project
        "packaging": "jar",
        "scope":     "compile"
      }
    }
  ]
}
```

## Binary entry

```jsonc
{
  "kind": "binary",
  "tool": "scalafmt",
  "version": "3.7.17",
  "platformSuffix": "x86_64-pc-linux",
  "url":    "https://github.com/scalameta/scalafmt/releases/download/v3.7.17/scalafmt-x86_64-pc-linux.gz",
  "sha256": "…",
  "extracted": {                              // for archives
    "format": "gz",
    "innerPath": "scalafmt"
  }
}
```

## Module identifier convention

`org:name` for plain Java artifacts.
`org::name` is *not* used in the lockfile — every name is fully qualified
post-suffixing (e.g. `org.scala-lang:scala3-library_3`). This avoids needing
the resolver to know `scalaBinaryVersion` at replay time.

For the `roots` field, we keep the `org::name` form as-written by the user
because that's what `dependency.AnyDependency` looks like before
`.toCs(scalaParams)`. The replay invokes the same `.toCs` to rebuild the
`coursier.Dependency` deterministically.

## Determinism rules

- `nodes` sorted by `module` ascending, then `version`, then `configuration`.
- `dependencies` arrays inside nodes sorted the same way.
- `artifacts` sorted by `classifier`, then `extension`.
- `buckets` map keys sorted lexicographically.
- All other arrays use stable insertion order if order is meaningful (only
  `roots` and `extraRepositories`); otherwise they are sorted.

## Required vs optional fields

Required for replay: `module`, `version`, `dependencies`, `url`, `sha256`.
Recommended: `size`, `pom.packaging`, `configuration`.
Optional: `pom.scope`, `optional` flag on artifacts (sources/javadoc).

## Forward-compat

Unknown fields ignored on read. Bumping `version` to 2 is a hard break and
forces a refresh.
