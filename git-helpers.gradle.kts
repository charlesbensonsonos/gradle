import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.api.provider.Property
import java.util.Properties

/**
 * Mixin providing [runGit] and [tryGit], small helpers for invoking `git` as an external
 * process. Mixed into each source's [ValueSourceParameters] so `obtain()` can shell out to git.
 */
interface GitCommand {
    // Runs git in [workingDir], returning trimmed stdout and failing loudly on a non-zero exit.
    fun runGit(workingDir: File, vararg args: String): String {
        val command = listOf("git", *args)
        val process = ProcessBuilder(command)
            .directory(workingDir)
            .redirectErrorStream(true)
            .also {
                // Never block on an interactive credential prompt; fail fast instead.
                it.environment()["GIT_TERMINAL_PROMPT"] = "0"
            }
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            val rule = "─".repeat(72)
            throw GradleException("""
                |
                |$rule
                |❌  git command failed
                |$rule
                |  Command : git ${args.joinToString(" ")}
                |  Dir     : $workingDir
                |  Exit    : $exitCode
                |  Output  : $output
                |$rule
                |
                """.trimMargin())
        }
        return output
    }

    // Runs git in [workingDir] like [runGit], but returns the exit code instead of
    // throwing on failure — for commands used as predicates or best-effort attempts.
    fun tryGit(workingDir: File, vararg args: String): Int {
        val process = ProcessBuilder(listOf("git", *args))
            .directory(workingDir)
            .redirectErrorStream(true)
            .also {
                // Never block on an interactive credential prompt; fail fast instead.
                it.environment()["GIT_TERMINAL_PROMPT"] = "0"
            }
            .start()
        process.inputStream.bufferedReader().readText()
        return process.waitFor()
    }
}

/**
 * A Gradle [ValueSource] provider that clones a git repository and fetches the
 * latest changes from its remote, then reports the SHA of the most recent commit
 * across all branches.
 *
 * Why a ValueSource? Running the clone/fetch here (rather than in a task or
 * directly in the build script) makes it a first-class configuration-cache
 * input. Gradle invokes [obtain] whenever the configuration cache is missing
 * or potentially stale, and treats the returned value as the cached input.
 * By returning the latest commit SHA, we tell Gradle to re-run the build logic
 * that depends on this source only when the remote actually advances.
 *
 * Cloning strategy:
 *  - The repository named by [Params.repo] is cloned into [Params.targetDirectory]
 *    (the clone runs in that directory's parent, using its name as the clone
 *    target), but only if that directory does not already exist.
 *  - If the directory exists but is not a clone of [Params.repo] — its origin remote
 *    points at a different repository (e.g. the configured repo changed), or it is not
 *    a git clone at all — it is deleted and recloned, with a warning. These clones are
 *    machine-managed, so a clone of the wrong remote cannot be deliberate local work.
 *  - Unless [Params.skipFetch] is set, `git fetch` updates the remote-tracking refs.
 *  - `git rev-list --branches --remotes --max-count=1` yields the SHA of the latest
 *    commit across all branches, which changes whenever any branch gets a new commit.
 *
 * Use the `cloneAndCheckoutGitRepositoryBranch` helper (registered on `extra` below)
 * instead of wiring this source up by hand.
 */
abstract class GitRepositorySource : ValueSource<String, GitRepositorySource.Params> {
    interface Params : ValueSourceParameters, GitCommand {
        /** Repository to clone: a GitHub `owner/name` (cloned over SSH) or a local `file://` URI. */
        val repo: Property<String>
        /** Local directory the repository is cloned into (the clone runs in its parent, using its name). */
        val targetDirectory: DirectoryProperty
        /** Skips the `git fetch` step, relying on whatever is already present locally. */
        val skipFetch: Property<Boolean>

        // optional
        /** Level at which progress is logged. Defaults to INFO when unset. */
        val logLevel: Property<LogLevel>
    }

    /**
     * Reduces a git remote URL to a comparable repository identity: the SSH and HTTPS
     * forms of the same GitHub repository (a clone may use either, given the HTTPS
     * fallback in [obtain]) and the plain `owner/name` shorthand all normalize to
     * `owner/name`. Other URLs (e.g. `file://`) are compared as-is, minus any trailing
     * `/` or `.git`.
     */
    private fun repoIdentity(url: String): String = url.trim()
        .removeSuffix("/")
        .removeSuffix(".git")
        .removePrefix("git@github.com:")
        .replace(Regex("^(?:https?|ssh)://(?:[^@/]+@)?github\\.com/"), "")

    /**
     * Clones the repository into `targetDirectory` if it does not already exist (an existing
     * directory that is not a clone of `repo` is deleted and recloned first), fetches the
     * latest changes from the remote (unless `skipFetch` is set), and returns the SHA of the
     * latest commit across all branches. See the class KDoc for the caching rationale.
     */
    override fun obtain(): String? {
        val logger = Logging.getLogger(GitRepositorySource::class.java)
        val logLevel = parameters.logLevel.orElse(LogLevel.INFO).get()
        val repo = parameters.repo.get()
        val targetDirectory = parameters.targetDirectory.get().asFile
        val skipFetch = parameters.skipFetch.get()

        logger.log(logLevel, "Fetching $repo in $targetDirectory")

        if (targetDirectory.exists()) {
            // The existing directory may not be a clone of [repo]: the configured repo may have
            // changed since it was cloned, or a previous clone may have been left broken. These
            // clones are machine-managed — a clone of the wrong remote cannot be deliberate
            // local work — so delete it and let the clone below recreate it from [repo].
            val origin = if (File(targetDirectory, ".git").exists()) {
                runCatching {
                    parameters.runGit(targetDirectory, "config", "--get", "remote.origin.url")
                }.getOrNull()
            } else {
                null
            }
            if (origin == null || repoIdentity(origin) != repoIdentity(repo)) {
                val reason = origin?.let { "its origin points at $it instead of $repo" }
                    ?: "it is not a git clone with an origin remote"
                logger.warn("  Deleting $targetDirectory and recloning: $reason")
                if (!targetDirectory.deleteRecursively()) {
                    throw GradleException("Could not delete $targetDirectory to reclone it from $repo — delete it manually and re-run")
                }
            }
        }

        if (!targetDirectory.exists()) {
            // A local `file://` repo is cloned as-is; anything else is treated as a
            // GitHub `owner/name` and cloned over SSH.
            var repoUri = if (repo.startsWith("file://")) {
                    repo
            } else {
                "git@github.com:$repo.git"
            }
            val parentFile = targetDirectory.parentFile
            parentFile.mkdirs()
            logger.log(logLevel, "  Cloning $repo into $parentFile")
            try {
                parameters.runGit(parentFile, "clone", repoUri, targetDirectory.name)
            } catch (e: GradleException) {
                // if we get an error while cloning from a file git repo, just throw
                if (repo.startsWith("file://")) throw e
                // otherwise, let's try again with HTTPS, as ssh might not be set up
                logger.warn("  Cloning $repo failed over ssh. Trying over https...")
                repoUri = "https://github.com/$repo.git"
                parameters.runGit(parentFile, "clone", repoUri, targetDirectory.name)
            }
        }

        if (!skipFetch) {
            logger.log(logLevel, "  Fetching latest changes")
            parameters.runGit(targetDirectory, "fetch")
        }

        // SHA of the latest commit across all branches (local + remote-tracking):
        // changes whenever any branch gets a new commit.
        val sha = parameters.runGit(targetDirectory, "rev-list", "--branches", "--remotes", "--max-count=1")
        logger.log(logLevel, "  Latest commit SHA across all branches is $sha")

        logger.log(logLevel, "done.")
        logger.log(logLevel, "")

        // Returning the latest commit SHA tells Gradle to watch this specific string for changes
        return sha
    }
}

/**
 * A Gradle [ValueSource] provider that checks out a branch in an already-cloned
 * repository, brings it in line with the already-fetched remote-tracking branch
 * (fast-forwarding when behind, preserving local commits when ahead, and
 * force-resetting only when the histories have diverged), and reports the
 * branch's tip commit SHA.
 *
 * Why a ValueSource? As with [GitRepositorySource], running the checkout here makes
 * it a first-class configuration-cache input: Gradle re-runs [obtain] on each build
 * and treats the returned tip SHA as the cached input, so build logic that depends on
 * this source re-runs only when the branch's tip changes.
 *
 * This expects the repository to already be present in [Params.targetDirectory]
 * (e.g. cloned and fetched by [GitRepositorySource]); it does not clone or fetch.
 */
abstract class GitBranchSource : ValueSource<String, GitBranchSource.Params> {
    interface Params : ValueSourceParameters, GitCommand {
        /** Branch to check out. */
        val branch: Property<String>
        /** Local directory containing the already-cloned repository. */
        val targetDirectory: DirectoryProperty

        // optional
        /** Level at which progress is logged. Defaults to INFO when unset. */
        val logLevel: Property<LogLevel>
    }

    /**
     * Checks out `branch` in the already-cloned `targetDirectory`, brings it in line with the
     * already-fetched `origin/<branch>` using tiered update logic, and returns the branch's
     * tip commit SHA:
     *
     *  1. If the local branch is equal to or **ahead** of `origin/<branch>`, the clone is left
     *     untouched — deliberate local (developer) commits on top of origin are preserved.
     *  2. If it is simply **behind**, it is fast-forwarded to `origin/<branch>`
     *     (`merge --ff-only`, a local operation — no network).
     *  3. Only if the histories have **diverged** (e.g. the upstream repository's history was
     *     rewritten/recreated), or the clone is in a broken state (e.g. left mid-merge), is this
     *     machine-managed clone force-reset to `origin/<branch>`, with a warning — a diverged
     *     local line cannot be developer work on top of the current origin.
     *
     * See the class KDoc for the caching rationale.
     */
    override fun obtain(): String? {
        val logger = Logging.getLogger(GitBranchSource::class.java)
        val logLevel = parameters.logLevel.orElse(LogLevel.INFO).get()
        val branch = parameters.branch.get()
        val targetDirectory = parameters.targetDirectory.get().asFile

        logger.log(logLevel, "Checking out \"$branch\" and updating it from origin/$branch in $targetDirectory")

        // Check out the branch, creating it from origin/<branch> only if it doesn't exist locally.
        // The checkout is allowed to fail here (e.g. a clone left mid-merge by the old merge-based
        // update): that state is never deliberate local work, so it falls through to the force
        // reset below instead of aborting the build.
        logger.log(logLevel, "  Checking out \"$branch\"")
        val checkedOut = if (parameters.tryGit(targetDirectory, "rev-parse", "--verify", "--quiet", "refs/heads/$branch") == 0) {
            parameters.tryGit(targetDirectory, "checkout", branch) == 0
        } else {
            parameters.tryGit(targetDirectory, "checkout", "-B", branch, "origin/$branch") == 0
        }

        if (checkedOut && parameters.tryGit(targetDirectory, "merge-base", "--is-ancestor", "origin/$branch", branch) == 0) {
            // origin/<branch> is an ancestor of the local branch: the clone is equal to or ahead
            // of origin. Leave it untouched — developers deliberately work on top of these clones
            // (often with fetching skipped), and their local commits must be preserved.
            if (parameters.runGit(targetDirectory, "rev-parse", branch) !=
                    parameters.runGit(targetDirectory, "rev-parse", "origin/$branch")) {
                logger.lifecycle("  Local commits in $targetDirectory are ahead of origin/$branch — leaving clone as-is (developer changes preserved)")
            }
        } else if (checkedOut && parameters.tryGit(targetDirectory, "merge", "--ff-only", "origin/$branch") == 0) {
            // The local branch was simply behind: it has been fast-forwarded to origin/<branch>.
            // (`--ff-only` never creates a merge state, so a failure here is side-effect free.)
            logger.log(logLevel, "  Fast-forwarded $branch to origin/$branch")
        } else {
            // The fast-forward was impossible (histories diverged or are unrelated — e.g. the
            // upstream repository's history was rewritten/recreated), or the checkout itself
            // failed (clone left mid-merge). A diverged local line cannot be developer work on
            // top of the current origin, and these clones are machine-managed, so force-reset to
            // origin: `-B` re-points the local branch at origin/<branch>; `-f` discards local
            // modifications and clears any in-progress merge.
            logger.warn("  Machine-managed clone in $targetDirectory has diverged from origin/$branch (upstream history rewritten?) — force-resetting it to origin/$branch")
            parameters.runGit(targetDirectory, "checkout", "-f", "-B", branch, "origin/$branch")
        }

        // Get the latest commit hash for this branch
        val sha = parameters.runGit(targetDirectory, "rev-parse", branch)
        logger.log(logLevel, "  Latest commit SHA for $branch is $sha")

        logger.log(logLevel, "done.")
        logger.log(logLevel, "")

        return sha
    }
}

/**
 * Builds the [Provider] backed by [GitBranchSource] that checks out `branch` in the
 * already-cloned `targetDirectory` and reports its tip commit SHA. This is the shared
 * helper the `checkoutGitRepositoryBranch` and `cloneAndCheckoutGitRepositoryBranch`
 * entry points delegate to; calling `.get()` on the returned provider performs the checkout.
 */
fun getCheckoutGitRepositoryBranchProvider(branch: String,
                                           targetDirectory: Directory,
                                           logLevel: LogLevel): Provider<String> {
    return providers.of(GitBranchSource::class.java) {
        parameters.branch.set(branch)
        parameters.targetDirectory.set(targetDirectory)
        parameters.logLevel.set(logLevel)
    }
}

/**
 * Convenience entry point, exposed via `extra` so it can be called from any
 * build script that applies this file:
 *
 *     @Suppress("UNCHECKED_CAST")
 *     val cloneAndCheckoutGitRepositoryBranch = extra["cloneAndCheckoutGitRepositoryBranch"]
 *             as (String, String, Directory, String?, LogLevel) -> Unit
 *     cloneAndCheckoutGitRepositoryBranch(
 *         "Sonos-Inc/gradle",
 *         "main",
 *         targetDirectory,
 *         null, // skipRemoteFetchProperty (null to always fetch)
 *         LogLevel.INFO
 *     )
 *
 * This will clone the repository (if needed) and fetch from the remote, then check out
 * `branch` and update it from the fetched remote-tracking branch (fast-forward when behind,
 * local commits preserved when ahead, force-reset only if the histories have diverged —
 * see [GitBranchSource.obtain]). Fetching is skipped
 * when Gradle is running offline, or via a property found in local.sonos.properties,
 * of the same name as what the [skipRemoteFetchProperty] argument provides.
 */
extra["cloneAndCheckoutGitRepositoryBranch"] = fun(repo: String,
                                                   branch: String,
                                                   targetDirectory: Directory,
                                                   skipRemoteFetchProperty: String?,
                                                   logLevel: LogLevel): Unit {
    // Fetching the repo will use a Provider<GitRepositorySource> which will cause configuration
    // to run again if any new commit exists on the remote, unless fetching is disabled
    // when running Gradle in offline mode, or (via a property found in local.sonos.properties,
    // of the same name as what the skipRemoteFetchProperty argument provides.
    val skipFetch = runCatching {
        File("local.sonos.properties").reader().use {
            val p = Properties()
            p.load(it)
            p.getProperty(skipRemoteFetchProperty)?.toBoolean()
        }
    }.getOrNull() ?: gradle.startParameter.isOffline
    providers.of(GitRepositorySource::class.java) {
        parameters.repo.set(repo)
        parameters.targetDirectory.set(targetDirectory)
        parameters.skipFetch.set(skipFetch)
        parameters.logLevel.set(logLevel)
    }.get()

    // checking out the branch will use a Provider<GitBranchSource> which will cause configuration
    // to run again if any new commit exists in the local branch
    getCheckoutGitRepositoryBranchProvider(branch, targetDirectory, logLevel).get()
}

/**
 * Convenience entry point, exposed via `extra`, for checking out a branch in a repository
 * that has already been cloned into `targetDirectory`:
 *
 *     @Suppress("UNCHECKED_CAST")
 *     val checkoutGitRepositoryBranch = extra["checkoutGitRepositoryBranch"]
 *             as (String, Directory, LogLevel) -> Unit
 *     checkoutGitRepositoryBranch("main", targetDirectory, LogLevel.INFO)
 *
 * Unlike `cloneAndCheckoutGitRepositoryBranch`, this neither clones nor fetches — it only
 * checks out `branch` and updates it from the already-fetched `origin/<branch>` (fast-forward
 * when behind, local commits preserved when ahead, force-reset only on divergence — see
 * [GitBranchSource.obtain]).
 */
extra["checkoutGitRepositoryBranch"] = fun(branch: String,
                                           targetDirectory: Directory,
                                           logLevel: LogLevel): Unit {
    // checking out the branch will use a Provider<GitBranchSource> which will cause configuration
    // to run again if any new commit exists in the local branch
    getCheckoutGitRepositoryBranchProvider(branch, targetDirectory, logLevel).get()
}

/**
 * Convenience entry point, exposed via `extra`, for returning a provider used for
 * checking out a branch in a repository that has already been cloned into `targetDirectory`:
 *
 *     @Suppress("UNCHECKED_CAST")
 *     val getCheckoutGitRepositoryBranchProvider = extra["getCheckoutGitRepositoryBranchProvider"]
 *             as (String, Directory, LogLevel) -> Provider<String>
 *     val provider = getCheckoutGitRepositoryBranchProvider("main", targetDirectory, LogLevel.INFO)
 *
 * Unlike `cloneAndCheckoutGitRepositoryBranch`, this neither clones nor fetches — it only
 * checks out `branch` and updates it from the already-fetched `origin/<branch>` (fast-forward
 * when behind, local commits preserved when ahead, force-reset only on divergence — see
 * [GitBranchSource.obtain]).
 */
extra["getCheckoutGitRepositoryBranchProvider"] = fun(branch: String,
                                                      targetDirectory: Directory,
                                                      logLevel: LogLevel): Provider<String> {
    return getCheckoutGitRepositoryBranchProvider(branch, targetDirectory, logLevel)
}
