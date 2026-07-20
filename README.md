# Gradle Git Helpers

[`git-helpers.gradle.kts`](git-helpers.gradle.kts) clones a git
repository at configuration time, fetches the latest changes, and checks out a
branch — so shared build logic can be versioned in one repo and pulled into many
projects without vendoring it.

It uses plain `git` (over SSH for a GitHub `owner/name`, or as-is for a local
`file://` URI), so it relies on your existing git credentials — **no token
parameter is required**.

## How it works

The work is split across two Gradle [`ValueSource`]s, each of which makes its
result a first-class **configuration-cache input**. Gradle re-runs a
`ValueSource`'s `obtain()` on every build to check whether its value changed, so
the build re-runs dependent logic only when the git state actually advances — no
manual version bump needed.

- **`GitRepositorySource`** — clones `repo` into `targetDirectory` (only if that
  directory doesn't already exist; the clone runs in its parent, using its name
  as the clone target), then runs `git fetch` unless fetching is skipped. An
  existing directory that is **not a clone of `repo`** — its origin remote
  points at a different repository (e.g. the configured repo changed), or it
  isn't a git clone at all — is deleted and recloned, with a warning: these
  clones are machine-managed, so a clone of the wrong remote cannot be
  deliberate local work. It
  returns the SHA of the **latest commit across all branches**
  (`git rev-list --branches --remotes --max-count=1`), which changes whenever any
  branch gets a new commit.
- **`GitBranchSource`** — checks out `branch` in an already-cloned
  `targetDirectory` and brings it in line with the already-fetched
  `origin/<branch>` (all local operations, no network), using tiered logic:
  if the local branch is equal to or **ahead** of origin it is left untouched
  (deliberate local commits — e.g. developer hacking on the clone — are
  preserved); if it is simply **behind** it is fast-forwarded
  (`merge --ff-only`); only if the histories have **diverged** (e.g. the
  upstream history was rewritten/recreated) or the clone is in a broken state
  (e.g. stuck mid-merge) is it force-reset to `origin/<branch>`
  (`checkout -f -B`), with a warning — a diverged local line cannot be
  developer work on top of the current origin, so nothing meaningful is lost.
  It returns the branch's **tip commit SHA**
  (`git rev-parse <branch>`), which changes when that branch's tip changes.

## Entry points

Three convenience functions are registered on `extra`:

- **`cloneAndCheckoutGitRepositoryBranch`** — the full path: clone (if needed),
  fetch, check out `branch`, and update it from the fetched remote-tracking
  branch (fast-forward when behind; local commits preserved when ahead;
  force-reset only on divergence). Backed by both value sources.
- **`checkoutGitRepositoryBranch`** — checkout only, for a repository that has
  **already** been cloned/fetched. It neither clones nor fetches; it just checks
  out `branch` and updates it from the already-fetched `origin/<branch>` (same
  tiered logic). Backed by `GitBranchSource`.
- **`getCheckoutGitRepositoryBranchProvider`** — same as
  `checkoutGitRepositoryBranch`, but returns the `Provider<String>` (the branch's
  tip SHA) instead of running the checkout eagerly. Call `.get()` on the returned
  provider to perform the checkout. Backed by `GitBranchSource`.

## Skipping the fetch (offline / pinned)

`cloneAndCheckoutGitRepositoryBranch` skips the `git fetch` (reusing whatever is
already cloned) when **either**:

- Gradle is run offline (`--offline`), or
- a `local.sonos.properties` file sets the property named by the
  `skipRemoteFetchProperty` argument to `true`.

The property name is caller-supplied: pass the key to look up (e.g.
`"com.sonos.skip-fetch-latest"`), or `null` to rely on offline mode
alone. The clone itself still happens if `targetDirectory` doesn't exist yet.

## Usage

### Clone, fetch, and check out a branch

```kotlin
// build.gradle.kts (or settings.gradle.kts)
apply(from = "git-helpers.gradle.kts")

@Suppress("UNCHECKED_CAST")
val cloneAndCheckoutGitRepositoryBranch =
    extra["cloneAndCheckoutGitRepositoryBranch"]
        as (String, String, Directory, String?, LogLevel) -> Unit

val repo = layout.buildDirectory.dir("gradle").get()

cloneAndCheckoutGitRepositoryBranch(
    "Sonos-Inc/gradle",            // repo: owner/name (SSH) or a file:// URI
    "main",                        // branch to check out and update
    repo,                          // local clone directory (Directory)
    "com.sonos.skip-fetch-latest", // skipRemoteFetchProperty (or null for offline-only)
    LogLevel.INFO,                 // log level for progress output
)

// The repo is now cloned/updated and the branch checked out; apply a script from it:
apply(from = repo.file("common.gradle.kts"))
```

### Check out a branch in an already-cloned repo

```kotlin
apply(from = "git-helpers.gradle.kts")

@Suppress("UNCHECKED_CAST")
val checkoutGitRepositoryBranch =
    extra["checkoutGitRepositoryBranch"]
        as (String, Directory, LogLevel) -> Unit

val repo = layout.buildDirectory.dir("gradle").get()

checkoutGitRepositoryBranch(
    "release/1.x",   // branch to check out and update
  repo,      // local directory of the already-cloned repo (Directory)
    LogLevel.INFO,   // log level for progress output
)

apply(from = repo.file("common.gradle.kts"))
```

## Parameters

### `cloneAndCheckoutGitRepositoryBranch`

| Parameter                 | Type        | Description                                                                                                     |
|---------------------------|-------------|-----------------------------------------------------------------------------------------------------------------|
| `repo`                    | `String`    | Repository to clone: a GitHub `owner/name` (SSH) or `file://` URI.                                              |
| `branch`                  | `String`    | Git branch to check out and update.                                                                             |
| `targetDirectory`         | `Directory` | Local directory the repo is cloned into (clone runs in its parent, using its name).                             |
| `skipRemoteFetchProperty` | `String?`   | Property name in `local.sonos.properties` that skips the fetch when `true`; `null` to rely on `--offline` only. |
| `logLevel`                | `LogLevel`  | Level at which progress is logged.                                                                              |

### `checkoutGitRepositoryBranch` (and `getCheckoutGitRepositoryBranchProvider`)

Both take the same parameters; `getCheckoutGitRepositoryBranchProvider` returns a
`Provider<String>` instead of `Unit`.

| Parameter         | Type        | Description                                                |
|-------------------|-------------|------------------------------------------------------------|
| `branch`          | `String`    | Git branch to check out and update.                        |
| `targetDirectory` | `Directory` | Local directory containing the already-cloned repository.  |
| `logLevel`        | `LogLevel`  | Level at which progress is logged.                         |

[`ValueSource`]: https://docs.gradle.org/current/userguide/configuration_cache.html#config_cache:requirements:external_processes
