package scala.build.tests

import coursier.cache.FileCache
import coursier.core.{Dependency, Module, Resolution}
import coursier.util.Task
import coursier.version.VersionConstraint

import scala.annotation.tailrec

/** Smoke tests verifying that the lockfile replay strategy described in
  * `notes/coursier-replay-verified.md` is sound against the actual Coursier
  * version on scala-cli's classpath.
  *
  * Each test runs a small live `Fetch` to obtain a real `Resolution`, then
  * reconstructs an equivalent `Resolution` purely from the captured
  * `projectCache0` + roots + forced versions, and asserts the reconstructed
  * one converges to the same artifact set without any further network or
  * repository access.
  */
class LockfileReplaySmokeTests extends TestUtil.ScalaCliBuildSuite {

  private val cache: FileCache[Task] = FileCache()

  /** Module/version pair used as the smoke-test dep. `geny_2.13` is small,
    * stable, and pulls scala-library transitively — enough graph to exercise
    * the cache without being slow.
    */
  private val genyDep = Dependency(
    Module(
      coursier.core.Organization("com.lihaoyi"),
      coursier.core.ModuleName("geny_2.13"),
      Map.empty
    ),
    VersionConstraint("1.1.1")
  )

  /** Run a live resolve so we have a real `Resolution` to copy from. */
  private def liveResolution(roots: Seq[Dependency]): coursier.Fetch.Result = {
    val fetch = coursier.Fetch()
      .withCache(cache)
      .addDependencies(roots*)
    fetch.eitherResult() match {
      case Right(r)  => r
      case Left(err) => fail(s"live fetch failed: $err")
    }
  }

  /** Drive a Resolution to fixpoint using only its existing projectCache0.
    * Returns the converged Resolution or fails if it would need to consult a
    * repository (i.e. `missingFromCache` is non-empty after one step).
    */
  private def converge(initial: Resolution): Resolution = {
    @tailrec def loop(r: Resolution, steps: Int): Resolution =
      if r.isDone then r
      else if steps <= 0 then
        fail(
          s"replay did not converge; missingFromCache=${r.missingFromCache}"
        )
      else loop(r.nextIfNoMissing, steps - 1)
    loop(initial, 16)
  }

  test("projectCache0 preload yields the same artifact set as a live resolve") {
    val live    = liveResolution(Seq(genyDep))
    val liveRes = live.resolution

    // Replay: build a fresh Resolution carrying only the captured cache,
    // roots, and force-versions. No repositories.
    val replayed = Resolution()
      .withRootDependencies(liveRes.rootDependencies)
      .withForceVersions0(liveRes.forceVersions0)
      .withProjectCache0(liveRes.projectCache0)

    val converged = converge(replayed)

    assert(converged.isDone, "replayed resolution did not reach isDone")
    assertEquals(
      converged.missingFromCache,
      Set.empty[(Module, VersionConstraint)],
      "replayed resolution still wants modules from a repository"
    )

    val liveDeps     = liveRes.dependencyArtifacts0().map(_._1).toSet
    val replayedDeps = converged.dependencyArtifacts0().map(_._1).toSet
    assertEquals(replayedDeps, liveDeps, "artifact dep set drifted under replay")
  }

  test("subset0 preserves projectCache0") {
    val live    = liveResolution(Seq(genyDep))
    val liveRes = live.resolution

    // Subset to a single root — exercises the same code path scala-cli uses
    // for the runtime classpath fork at Artifacts.scala:473.
    val subsetted = liveRes.subset0(Seq(genyDep)) match {
      case Right(r)  => r
      case Left(err) => fail(s"subset0 failed: $err")
    }

    assert(
      subsetted.projectCache0.nonEmpty,
      "subset0 dropped projectCache0 entirely"
    )
    // Every module reachable from the new roots must still have a Project
    // in the cache, otherwise the runtime fork would have to re-fetch POMs.
    val reachable = subsetted.dependencyArtifacts0().map(_._1.module).toSet
    val cached    = subsetted.projectCache0.keySet.map(_._1)
    val gap       = reachable -- cached
    assertEquals(
      gap,
      Set.empty[Module],
      s"subset0 left these modules without Projects in the cache: $gap"
    )

    // And the subset must converge with no missing entries — i.e. it's
    // self-contained and can be replayed offline.
    val converged = converge(subsetted)
    assertEquals(
      converged.missingFromCache,
      Set.empty[(Module, VersionConstraint)]
    )
  }

  test("forceVersions0 matching the cache is a no-op during replay") {
    val live    = liveResolution(Seq(genyDep))
    val liveRes = live.resolution

    // Pick scala-library — it's transitively in geny's graph, so it has a
    // pinned version in projectCache0. Force-pin it to the exact same
    // version it already resolved to. This should not perturb the
    // replayed graph at all.
    val (scalaLib, scalaLibVer) = liveRes.projectCache0.keys
      .collectFirst {
        case (m, v) if m.name.value.startsWith("scala-library") => (m, v)
      }
      .getOrElse(fail("no scala-library in liveRes.projectCache0"))

    val replayed = Resolution()
      .withRootDependencies(liveRes.rootDependencies)
      .withForceVersions0(liveRes.forceVersions0 + (scalaLib -> scalaLibVer))
      .withProjectCache0(liveRes.projectCache0)

    val converged = converge(replayed)

    assertEquals(
      converged.missingFromCache,
      Set.empty[(Module, VersionConstraint)],
      "forced-but-already-pinned version triggered a repository lookup"
    )

    val liveDeps     = liveRes.dependencyArtifacts0().map(_._1).toSet
    val replayedDeps = converged.dependencyArtifacts0().map(_._1).toSet
    assertEquals(replayedDeps, liveDeps, "forceVersions0 perturbed the graph")
  }
}
