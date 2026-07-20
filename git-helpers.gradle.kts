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

    // Runs git in [workingDir] like [runGit], but returns whether it succeeded instead
    // of throwing — for commands used as predicates or best-effort attempts.
    fun tryGit(workingDir: File, vararg args: String): Boolean =
        runCatching { runGit(workingDir, *args) }.isSuccess
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
 *  - An existing directory that can no longer be updated from [Params.repo] is deleted
 *    and recloned, with a warning: its origin points at a different repository (the
 *    configured repo changed), it is stuck mid-merge, or — after the fetch — its
 *    checked-out branch has diverged from origin (upstream history rewritten or
 *    force-pushed, so origin can no longer be merged). A clone that is merely ahead of
 *    origin (deliberate local commits) or behind it (fast-forwardable) is left in place.
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
     * Clones the repository into `targetDirectory` if it does not already exist, fetches the
     * latest changes from the remote (unless `skipFetch` is set), and returns the SHA of the
     * latest commit across all branches. An existing clone that can no longer be updated from
     * `repo` (wrong origin, stuck mid-merge, or diverged from a rewritten upstream) is deleted
     * and recloned. See the class KDoc for the caching rationale.
     */
    override fun obtain(): String? {
        val logger = Logging.getLogger(GitRepositorySource::class.java)
        val logLevel = parameters.logLevel.orElse(LogLevel.INFO).get()
        val repo = parameters.repo.get()
        val targetDirectory = parameters.targetDirectory.get().asFile
        val skipFetch = parameters.skipFetch.get()

        logger.log(logLevel, "Fetching $repo in $targetDirectory")

        fun clone() {
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

        // These clones are machine-managed: one that can no longer be updated in place is
        // deleted, with a warning, and recloned from [repo] rather than left for manual repair.
        fun deleteStaleClone(reason: String) {
            logger.warn("  Machine-managed clone in $targetDirectory $reason — deleting it and recloning")
            if (!targetDirectory.deleteRecursively()) {
                throw GradleException("Could not delete $targetDirectory to reclone it from $repo — delete it manually and re-run")
            }
        }

        if (targetDirectory.exists()) {
            // The URI forms clone() can produce for [repo] (SSH first, HTTPS fallback).
            val expected = if (repo.startsWith("file://")) {
                listOf(repo)
            } else {
                listOf("git@github.com:$repo.git", "https://github.com/$repo.git")
            }
            val origin = if (File(targetDirectory, ".git").exists()) {
                runCatching {
                    parameters.runGit(targetDirectory, "config", "--get", "remote.origin.url")
                }.getOrNull()
            } else {
                null
            }
            if (origin !in expected) {
                deleteStaleClone("is not a clone of $repo (origin: $origin)")
            } else if (parameters.tryGit(targetDirectory, "rev-parse", "--verify", "--quiet", "MERGE_HEAD")) {
                deleteStaleClone("is stuck mid-merge")
            }
        }

        if (!targetDirectory.exists()) {
            clone()
        }

        if (!skipFetch) {
            logger.log(logLevel, "  Fetching latest changes")
            parameters.runGit(targetDirectory, "fetch")

            // The fetch may reveal that origin can no longer be merged or pulled: the checked-out
            // branch and its origin counterpart have diverged, e.g. because the upstream history
            // was rewritten or force-pushed. The clone cannot be updated in place then — but one
            // that is merely ahead of origin (deliberate local commits, which must be preserved)
            // or behind it (fast-forwardable) is left alone.
            val branch = runCatching {
                parameters.runGit(targetDirectory, "rev-parse", "--abbrev-ref", "HEAD")
            }.getOrNull()
            val diverged = branch != null &&
                    parameters.tryGit(targetDirectory, "rev-parse", "--verify", "--quiet", "refs/remotes/origin/$branch") &&
                    !parameters.tryGit(targetDirectory, "merge-base", "--is-ancestor", "origin/$branch", branch) &&
                    !parameters.tryGit(targetDirectory, "merge-base", "--is-ancestor", branch, "origin/$branch")
            if (diverged) {
                deleteStaleClone("has diverged from origin/$branch (upstream history rewritten or force-pushed?)")
                clone()
            }
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
 * repository, fast-forwards it to the already-fetched remote-tracking branch, and
 * reports the branch's tip commit SHA.
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
     * Checks out `branch` in the already-cloned `targetDirectory`, merges the already-fetched
     * `origin/<branch>` to bring it up to date, and returns the branch's tip commit SHA.
     * See the class KDoc for the caching rationale.
     */
    override fun obtain(): String? {
        val logger = Logging.getLogger(GitBranchSource::class.java)
        val logLevel = parameters.logLevel.orElse(LogLevel.INFO).get()
        val branch = parameters.branch.get()
        val targetDirectory = parameters.targetDirectory.get().asFile

        logger.log(logLevel, "Checking out \"$branch\" and pulling latest in $targetDirectory")

        // Check out the branch
        logger.log(logLevel, "  Checking out \"$branch\"")
        parameters.runGit(targetDirectory, "checkout", branch)

        // Bring it up to date with the already-fetched origin/<branch> (local merge, no network)
        logger.log(logLevel, "  Pulling latest changes for $branch")
        parameters.runGit(targetDirectory, "merge", "origin/$branch")

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
 * `branch` and fast-forward it to the fetched remote-tracking branch. Fetching is skipped
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
 * checks out `branch` and fast-forwards it to the already-fetched `origin/<branch>`.
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
 * checks out `branch` and fast-forwards it to the already-fetched `origin/<branch>`.
 */
extra["getCheckoutGitRepositoryBranchProvider"] = fun(branch: String,
                                                      targetDirectory: Directory,
                                                      logLevel: LogLevel): Provider<String> {
    return getCheckoutGitRepositoryBranchProvider(branch, targetDirectory, logLevel)
}
