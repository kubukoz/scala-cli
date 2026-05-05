# Coursier resolution call sites in scala-cli

Catalogued from current branch `native-export-options`. The lockfile must be
able to replay every resolution that happens here without going to the network.

Each entry: file:line → root deps → bucket key.

## User classpath (per scope, per platform)

**`modules/options/.../Artifacts.scala:452`** — `fetchAnyDependenciesWithResult`
- Roots: `defaultDependencies ++ extraDependencies ++ compileOnlyDependencies`
  + injected internal deps (test-runner, java-test-runner, JMH, jsTestBridge,
    nativeTestInterface, scalapy)
- Forced versions: pinned scala-library / scala3-library at `scalaVersion`
- Bucket: `user.<scope>.<platform>` per `(scopeName, Platform, scalaVersion,
  platformVersion)`
- Classifiers: optionally `sources`, plus main jars
- This is the only bucket whose graph is presented to the user as "their"
  dependencies. Everything else below is tooling.

## Scala compiler / bridge / scaladoc / runner / test-runner

`Artifacts.scala`:
- `:259` compiler dependencies — `org.scala-lang::scala3-compiler:<sv>` or
  `org.scala-lang:scala-compiler:<sv>` (Scala 2). Bucket:
  `tooling.scala-compiler.<sv>`.
- `:269` sbt bridge — `scala3-sbt-bridge:<sv>` or `scala2-sbt-bridge:<sv>`.
  Bucket: `tooling.scala-bridge.<sv>`.
- `:236` user compiler plugins (resolved with `intransitive=true`). Bucket:
  `tooling.compiler-plugins.<sv>` (one resolution per plugin in the current
  code, but they could be batched).
- `:539` JVM runner — `runnerOrganization::runnerModuleName:<runnerVersion>`,
  intransitive. Bucket: `tooling.runner.<sv>.<runnerVersion>`.
- `Doc.scala:230` scaladoc — `org.scala-lang::scaladoc:<sv>`. Bucket:
  `tooling.scaladoc.<sv>`.
- Note: test-runner / java-test-runner / JMH / jsTestBridge /
  nativeTestInterface / scalapy are *not* separate buckets today — they're
  appended to the user fetch (`Artifacts.scala:420-462`). So a lockfile entry
  for the user bucket already covers them. Worth flagging in docs because
  changing `using test.dep` invalidates the user bucket but not the compiler
  buckets, while bumping Scala invalidates both.

## Scala.js tooling

`Artifacts.scala`:
- `:303` Scala.js CLI launcher —
  `org.virtuslab.scala-cli:scalajscli_2.13:<scalaJsCliVersion>+`, with
  `scalajs-linker_2.13` forced to the requested `scalaJsVersion`. Bucket:
  `tooling.scalajs-cli.<jsCliVersion>.<jsVersion>`. Always `_2.13` regardless
  of user's Scala.

`ScalaJsLinker.scala:71` — alternative path that fetches the linker JARs
directly (without the CLI wrapper), used when running the linker in-process.
Roots: `org.scala-js::scalajs-linker:<jsVersion>` (and friends). Bucket:
`tooling.scalajs-linker.<jsVersion>`. Same target, different launching
strategy.

`ScalaJsOptions.scala:47,52` — `scalajs-library` and `scalajs-compiler` are
returned as **user-classpath additions** for JS builds, *not* fetched in a
separate bucket. They flow through the user bucket above. The lockfile must
record them, but the bucket is the user one.

## Scala Native tooling

`Artifacts.scala:345` — Scala Native CLI launcher —
`org.scala-native:scala-native-cli_2.12:<nativeCliVersion>`. Bucket:
`tooling.scala-native-cli.<nativeCliVersion>`. Always `_2.12`.

`ScalaNativeOptions.scala:137` — `nativeDependencies(scalaVersion)` returns
runtime deps `scalalib` / `scala3lib` / `javalib` etc. — these go into the
user bucket like the JS library does.

`ScalaNativeOptions.scala:151` — `compilerPlugins` returns `nscplugin`. This
flows through `compilerPlugins0` (the intransitive plugin path above) so it's
in `tooling.compiler-plugins.<sv>`. Worth a note: `nscplugin` is keyed by
*native* version, not just scala version, so a stricter bucket key is
`tooling.compiler-plugins.<sv>.<nativeVersion>` for native-platform builds.

## Bloop

`Bloop.scala:71-115` — `bloop-frontend_2.12:<bloopVersion>` resolved via
`Artifacts.artifacts`. Bucket: `tooling.bloop.<bloopVersion>`. Bloop manages
its own runtime cache afterwards (`~/.cache/bloop`) — the lockfile only owns
the *classpath used to launch* Bloop.

## REPL

`ReplArtifacts.scala:43-83` — Ammonite path. Roots: user deps + scalapy +
`com.lihaoyi:::ammonite:<ammVer>`. Bucket: `tooling.ammonite.<sv>.<ammVer>`.
Note: Ammonite's classpath is mixed with user deps in a single resolution —
the bucket is essentially "user deps but with Ammonite forced in", so we
*can't* trivially share with the regular user bucket. Treat as its own
bucket.

`ReplArtifacts.scala:86-171` — default REPL. Roots:
`scala3-compiler:<sv>` (and on new Scala 3.8+, also `scala3-repl:<sv>`),
plus user deps + scalapy. Bucket: `tooling.repl.<sv>`.

## Scalafix

`ScalafixArtifacts.scala`:
- `:96` `scalafix-interfaces:<scalafixVersion>` — bucket
  `tooling.scalafix-interfaces.<scalafixVersion>`. Used to read a properties
  file off the JAR, then discarded.
- `:38` `scalafix-cli_<fetchSv>:<scalafixVersion>` — bucket
  `tooling.scalafix-cli.<sv>.<scalafixVersion>`. Note `<fetchSv>` is derived
  from a properties file inside scalafix-interfaces, so caching this requires
  caching the *resolved* fetchSv too.
- `:58` external rules (user `using scalafix.dep ...`). Bucket
  `tooling.scalafix-rules.<sv>` (Scala 3 maps to 2.13 per scalafix's design).

## Doc command

`Doc.scala:229` already covered (`scaladoc:<sv>`).

## Package command

`Package.scala:869` — `python-interface` (only when
`doSetupPython=true`). Bucket: `tooling.python-interface`.
`Package.scala:946` — `coursier.Artifacts.artifacts0` operating on the
already-resolved user resolution; not a new resolution, no new bucket.

## External binaries (not coursier resolutions but adjacent)

These hit Maven for a *single artifact* (a launcher jar or native binary)
rather than running a Coursier resolution. They're still cacheable in the
lockfile but with a simpler shape — just `(coords, classifier, url, sha)`.
- `FetchExternalBinary.scala` (used by `Fmt`, `PgpExternalCommand`,
  `JavaParserProxyBinary`, `ScalaJsLinker` fallback, `LibSodiumJni`)
- Buckets: `binary.scalafmt.<ver>`, `binary.pgp.<ver>`,
  `binary.scalajs-cli-bin.<ver>`, `binary.libsodium-jni.<ver>`,
  `binary.javaparser.<ver>`.

## Out of scope

- Bloop's own runtime cache (`~/.cache/bloop`) — Bloop owns it.
- LLVM toolchain (clang, lld) — not Coursier artifacts.
- JVM downloads (`coursier-jvm`) — separate cache, separate config; could be
  added later as `jvm.<index-id>.<version>` buckets.
