#!/usr/bin/env bash
# Validation harness for git-helpers.gradle.kts.
#
# Drives the real script through Gradle (apply(from = …) + the three extra entry
# points) against throwaway local file:// upstream repositories in a temp directory.
# No network, no credentials; nothing outside the temp directory is touched.
#
# Requirements: bash, git, and gradle on the PATH.
#
# Usage: tests/validate.sh
set -u

for tool in git gradle; do
  command -v "$tool" >/dev/null || { echo "error: $tool not found on PATH" >&2; exit 1; }
done

HELPERS="$(cd "$(dirname "$0")/.." && pwd)/git-helpers.gradle.kts"
BASE="$(mktemp -d)"
trap 'rm -rf "$BASE"' EXIT
CLONE="$BASE/proj/build/clone"

PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS  $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL  $1"; }
say() { echo; echo "── $1"; }

# Run gradle in the scratch project; output lands in $OUT
G() { OUT="$(cd "$BASE/proj" && gradle help --no-daemon --console=plain "$@" 2>&1)"; }
has()  { grep -qiE "$1" <<<"$OUT"; }
gitu() { git -C "$BASE/$1" -c user.email=t@t -c user.name=t "${@:2}"; }
tip()  { git -C "$BASE/$1" rev-parse "${2:-HEAD}"; }

say "setup: upstreams and scratch project in $BASE"
for n in upstreamA upstreamB; do
  mkdir -p "$BASE/$n"; gitu "$n" init -q -b main
  echo "$n v1" > "$BASE/$n/file.txt"; gitu "$n" add .; gitu "$n" commit -qm "initial $n"
done
A="file://$BASE/upstreamA"; B="file://$BASE/upstreamB"

mkdir -p "$BASE/proj"
echo 'rootProject.name = "git-helpers-validation"' > "$BASE/proj/settings.gradle.kts"
cat > "$BASE/proj/build.gradle.kts" <<EOF
apply(from = "$HELPERS")

val upstream: String = (findProperty("upstream") as String?) ?: "$A"
val branch: String = (findProperty("branch") as String?) ?: "main"
val mode: String = (findProperty("mode") as String?) ?: "full"
val cloneDir = layout.buildDirectory.dir("clone").get()

when (mode) {
    "full" -> {
        @Suppress("UNCHECKED_CAST")
        val cloneAndCheckoutGitRepositoryBranch = extra["cloneAndCheckoutGitRepositoryBranch"]
                as (String, String, Directory, String?, LogLevel) -> Unit
        cloneAndCheckoutGitRepositoryBranch(upstream, branch, cloneDir, "test.skip.fetch", LogLevel.LIFECYCLE)
    }
    "checkoutOnly" -> {
        @Suppress("UNCHECKED_CAST")
        val checkoutGitRepositoryBranch = extra["checkoutGitRepositoryBranch"]
                as (String, Directory, LogLevel) -> Unit
        checkoutGitRepositoryBranch(branch, cloneDir, LogLevel.LIFECYCLE)
    }
    "provider" -> {
        @Suppress("UNCHECKED_CAST")
        val getCheckoutGitRepositoryBranchProvider = extra["getCheckoutGitRepositoryBranchProvider"]
                as (String, Directory, LogLevel) -> Provider<String>
        println("PROVIDER_SHA=" + getCheckoutGitRepositoryBranchProvider(branch, cloneDir, LogLevel.LIFECYCLE).get())
    }
}
EOF

say "1. fresh clone (cloneAndCheckoutGitRepositoryBranch)"
G
has "BUILD SUCCESSFUL" && [ "$(cat "$CLONE/file.txt")" = "upstreamA v1" ] && ok "clones and checks out main" || bad "fresh clone"
has "Latest commit SHA across all branches is $(tip upstreamA)" && ok "logs repo-wide latest SHA" || bad "repo-wide SHA log"

say "2. re-run, nothing changed: clone untouched"
touch "$CLONE/marker.txt"; G
! has "deleting" && [ -f "$CLONE/marker.txt" ] && ok "no reclone, untracked file survives" || bad "no-op rerun"

say "3. upstream advances: fast-forward via normal merge"
echo "upstreamA v2" > "$BASE/upstreamA/file.txt"; gitu upstreamA commit -qam "v2"
G
! has "deleting" && [ "$(cat "$CLONE/file.txt")" = "upstreamA v2" ] && ok "fast-forwarded, no reclone" || bad "fast-forward"

say "4. skip fetch via local.sonos.properties (test.skip.fetch=true)"
echo "upstreamA v3" > "$BASE/upstreamA/file.txt"; gitu upstreamA commit -qam "v3"
echo "test.skip.fetch=true" > "$BASE/proj/local.sonos.properties"
G
! has "Fetching latest changes" && [ "$(cat "$CLONE/file.txt")" = "upstreamA v2" ] && ok "fetch skipped, stays on v2" || bad "property skip"
rm "$BASE/proj/local.sonos.properties"

say "5. skip fetch via --offline"
G --offline
! has "Fetching latest changes" && [ "$(cat "$CLONE/file.txt")" = "upstreamA v2" ] && ok "offline skips fetch" || bad "offline skip"
G
[ "$(cat "$CLONE/file.txt")" = "upstreamA v3" ] && ok "online again picks up v3" || bad "post-offline catch-up"

say "6. developer commits ahead of origin: preserved"
echo "local hack" >> "$CLONE/file.txt"
git -C "$CLONE" -c user.email=t@t -c user.name=t commit -qam "local hack"
LOCAL_SHA=$(git -C "$CLONE" rev-parse HEAD)
G
! has "deleting" && [ "$(git -C "$CLONE" rev-parse HEAD)" = "$LOCAL_SHA" ] && ok "ahead clone untouched across run" || bad "ahead preservation"

say "7. upstream history rewritten (force push): reclone"
gitu upstreamA commit -q --amend -m "rewritten"
G
has "has diverged from origin/main" && has "deleting it and recloning" && ok "divergence detected, warned" || bad "divergence warning"
[ "$(git -C "$CLONE" log -1 --format=%s)" = "rewritten" ] && ok "healed onto rewritten history" || bad "heal"
G
! has "deleting" && ok "second run idempotent" || bad "idempotence"

say "8. clone stuck mid-merge: reclone"
git -C "$CLONE" rev-parse HEAD > "$CLONE/.git/MERGE_HEAD"
G
has "stuck mid-merge" && [ ! -f "$CLONE/.git/MERGE_HEAD" ] && ok "mid-merge clone recloned" || bad "mid-merge heal"

say "9. repo pointer changed: reclone from new upstream"
G -Pupstream="$B"
has "is not a clone of" && [ "$(cat "$CLONE/file.txt")" = "upstreamB v1" ] && ok "recloned from upstreamB" || bad "pointer change"

say "10. directory exists but isn't a git clone: reclone"
rm -rf "$CLONE"; mkdir -p "$CLONE"; echo junk > "$CLONE/junk.txt"
G -Pupstream="$B"
has "deleting it and recloning" && [ ! -f "$CLONE/junk.txt" ] && ok "junk dir replaced by clone" || bad "junk dir"

say "11. nonexistent file:// repo: fails loudly (no https fallback)"
G -Pupstream="file://$BASE/nonexistent"
has "BUILD FAILED" && has "git command failed" && ! has "Trying over https" && ok "clear failure, no fallback for file://" || bad "bogus repo failure"

say "12. commit on a side branch changes the repo-wide SHA (config re-run trigger)"
rm -rf "$CLONE"; G   # fresh clone of A
gitu upstreamA checkout -qb dev; echo dev > "$BASE/upstreamA/dev.txt"
gitu upstreamA add .; gitu upstreamA commit -qm "dev work"; gitu upstreamA checkout -q main
G
has "Latest commit SHA across all branches is $(tip upstreamA dev)" && ok "side-branch commit moves repo-wide SHA" || bad "side-branch SHA"
has "Latest commit SHA for main is $(tip upstreamA main)" && ok "branch tip SHA unaffected" || bad "branch tip SHA"

say "13. switching the requested branch"
gitu upstreamA checkout -qb feature; echo feat > "$BASE/upstreamA/feat.txt"
gitu upstreamA add .; gitu upstreamA commit -qm "feature work"; gitu upstreamA checkout -q main
G -Pbranch=feature
[ "$(git -C "$CLONE" rev-parse --abbrev-ref HEAD)" = "feature" ] && [ -f "$CLONE/feat.txt" ] && ok "checked out feature" || bad "branch switch"
G -Pbranch=main
[ "$(git -C "$CLONE" rev-parse --abbrev-ref HEAD)" = "main" ] && ok "switched back to main" || bad "switch back"

say "14. checkoutGitRepositoryBranch: no clone, no fetch"
echo "upstreamA v4" > "$BASE/upstreamA/file.txt"; gitu upstreamA commit -qam "v4"
G -Pmode=checkoutOnly
! has "Fetching latest changes" && ! has "Cloning" && ! grep -q "v4" "$CLONE/file.txt" && ok "checkout-only ignores new upstream commit" || bad "checkout-only"
G   # full mode catches up
grep -q "v4" "$CLONE/file.txt" && ok "full mode then catches up to v4" || bad "catch-up"

say "15. getCheckoutGitRepositoryBranchProvider returns the branch tip SHA"
G -Pmode=provider
has "PROVIDER_SHA=$(git -C "$CLONE" rev-parse main)" && ok "provider value == branch tip" || bad "provider value"

say "16. nonexistent branch: fails loudly"
G -Pmode=checkoutOnly -Pbranch=does-not-exist
has "BUILD FAILED" && has "git command failed" && ok "clear failure on bad branch" || bad "bad branch failure"

say "17. configuration cache: store / reuse / invalidate on upstream change"
rm -rf "$BASE/proj/.gradle"
G --configuration-cache
has "Configuration cache entry stored" && ok "entry stored" || bad "cc store"
G --configuration-cache
has "Configuration cache entry reused" && ok "entry reused when nothing changed" || bad "cc reuse"
echo "upstreamA v5" > "$BASE/upstreamA/file.txt"; gitu upstreamA commit -qam "v5"
G --configuration-cache
! has "Configuration cache entry reused" && has "BUILD SUCCESSFUL" && grep -q "v5" "$CLONE/file.txt" && ok "upstream commit invalidates entry, build re-runs" || bad "cc invalidation"

echo
echo "══════════════════════════════════"
echo " RESULT: $PASS passed, $FAIL failed"
echo "══════════════════════════════════"
exit $((FAIL > 0))
