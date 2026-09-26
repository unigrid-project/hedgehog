#!/usr/bin/env bash
#
# Cuts and publishes a Hedgehog release in two steps.
#
#   release.sh cut      tags the version the pom names and pushes the tag,
#                       which makes GitHub build the executables and draft
#                       the release
#   release.sh publish  signs the drafted assets and the bootstrap with the
#                       release key, attaches them and publishes the draft
#   release.sh dev      tags X.Y.Z-dev.N on a commit off master and pushes
#                       the tag; publish then turns it into a prerelease
#
# No version is ever passed: the pom carries X.Y.Z-SNAPSHOT, and dropping the
# suffix is the release. A dev release numbers the builds leading up to it,
# so 0.0.8-dev.1 < 0.0.8-dev.2 < 0.0.8 as SemVer orders them. Nothing is
# signed anywhere but on this machine, so the release key never has to leave it.

set -euo pipefail

readonly ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly RELEASE_KEY="$ROOT/release-key.asc"
readonly WORKFLOW="release.yml"
readonly DIST="$ROOT/dist"
readonly SCRATCH="$(mktemp -d)"
trap 'rm -rf "$SCRATCH"' EXIT

die() { printf '%s\n' "$*" >&2; exit 1; }
step() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
is_version() { [[ "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; }
is_dev_version() { [[ "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+-dev\.[0-9]+$ ]]; }

usage() {
	cat <<'EOF'
Usage: release.sh cut [--skip-tests] [--dry-run] [--next X.Y.Z]
       release.sh dev [--skip-tests]
       release.sh publish [--tag vX.Y.Z[-dev.N]] [--bootstrap FILE]
                          [--codename NAME] [--notes-file FILE]

cut      Builds and tests the whole project at the release version, turns
         the pom's X.Y.Z-SNAPSHOT into the release X.Y.Z with a commit and
         a tag, opens the next snapshot and pushes master and the tag. The
         tag reaching GitHub builds the executables and drafts the release.
           --skip-tests   build without running the test suite
           --dry-run      rehearse release:prepare; commit, tag and push nothing
           --next X.Y.Z   the snapshot to open afterwards (default: patch + 1)

dev      Builds and tests the release the pom works towards as X.Y.Z-dev.N,
         N one past the newest dev tag of X.Y.Z, commits that version off
         master, tags it and pushes only the tag. Master keeps its snapshot.
           --skip-tests   build without running the test suite

publish  Waits for the draft the tag produced, checks the bootstrap against
         the keys built into that release, signs every asset with the release
         key, attaches the signatures and bootstrap.dat.gz, and publishes. A
         dev tag is published as a prerelease, never as the latest release.
           --tag TAG         the release to publish (default: the newest tag)
           --bootstrap FILE  a signed bootstrap.dat, gzipped or not; without
                             it the bootstrap already on the draft or on the
                             latest release is used
           --codename NAME   titles the release "X.Y.Z - NAME"; not for dev
           --notes-file FILE release notes replacing the generated ones
EOF
}

pom_version() {
	mvn -q -N help:evaluate -Dexpression=project.version -DforceStdout
}

# The JDK Maven builds with is one that can run what it built.
maven_java() {
	printf '%s/bin/java' "$(mvn -q -N help:evaluate -Dexpression=java.home -DforceStdout)"
}

release_fingerprint() {
	gpg --show-keys --with-colons "$RELEASE_KEY" | awk -F: '/^fpr/ { print $10; exit }'
}

require_signing_key() {
	local fingerprint
	fingerprint="$(release_fingerprint)"
	gpg --list-secret-keys "$fingerprint" >/dev/null 2>&1 \
		|| die "The secret half of the release key $fingerprint is not in this keyring."
	printf '%s' "$fingerprint"
}

require_gh() {
	gh auth status >/dev/null 2>&1 || die "gh is not logged in; run 'gh auth login' first."
}

require_clean_master() {
	git rev-parse --git-dir >/dev/null 2>&1 || die "Not a git repository."
	[ "$(git rev-parse --abbrev-ref HEAD)" = master ] || die "Releases are cut from master."

	local dirty
	dirty="$(git status --porcelain)"
	[ -z "$dirty" ] || die "Working tree is not clean; commit or stash first:
$dirty"
}

# The release the pom works towards: its X.Y.Z-SNAPSHOT without the suffix.
upcoming_version() {
	local pom version
	pom="$(pom_version)"
	version="${pom%-SNAPSHOT}"
	if [ "$version" = "$pom" ] || ! is_version "$version"; then
		die "The pom reads '$pom', which names no version to release.
Set it to a major.minor.patch snapshot first."
	fi
	printf '%s' "$version"
}

# Counted over local and remote tags, so a dev tag pushed from another clone
# is never reused.
next_dev_number() {
	local pattern="v${1//./\\.}-dev\.\([0-9]\+\)"
	local newest
	newest="$({ git tag -l "v$1-dev.*"; git ls-remote --tags origin "refs/tags/v$1-dev.*" | sed 's|.*refs/tags/||'; } \
		| sed -n "s/^$pattern\$/\1/p" | sort -n | tail -1)"
	printf '%s' "$(( ${newest:-0} + 1 ))"
}

newest_tag() {
	git for-each-ref --sort=-creatordate --count=1 --format='%(refname:short)' 'refs/tags/v*'
}

bumped_patch() {
	local major="${1%%.*}" rest="${1#*.}"
	printf '%s.%s.%s' "$major" "${rest%%.*}" "$(( ${rest#*.} + 1 ))"
}

confirm() {
	printf '%s' "$1"
	if [ -t 0 ]; then
		printf ' [Y/n] '
		read -r answer
		case "$answer" in
			""|y|Y|yes) ;;
			*) die "Nothing done." ;;
		esac
	else
		printf '\n'
	fi
}

cut_release() {
	local tests=all dry_run=no next=""

	while [ $# -gt 0 ]; do
		case "$1" in
			--skip-tests) tests=none ;;
			--dry-run) dry_run=yes ;;
			--next) next="${2:?--next needs a version}"; shift ;;
			*) die "Unknown option for cut: $1" ;;
		esac
		shift
	done

	cd "$ROOT"
	require_clean_master
	require_gh
	require_signing_key >/dev/null

	[ -x "${GRAALVM_HOME:-}/bin/native-image" ] \
		|| die "GRAALVM_HOME must point at a GraalVM with native-image; release:prepare builds the launcher."

	local version tag
	version="$(upcoming_version)"
	tag="v$version"

	git rev-parse -q --verify "refs/tags/$tag" >/dev/null && die "Tag $tag already exists locally."
	[ -z "$(git ls-remote --tags origin "refs/tags/$tag")" ] || die "Tag $tag already exists on origin."

	if [ -n "$next" ]; then
		is_version "$next" || die "--next takes major.minor.patch, not '$next'."
	else
		next="$(bumped_patch "$version")"
	fi

	if [ "$dry_run" = no ]; then
		confirm "Release $version, open $next-SNAPSHOT and push master and $tag?"
	fi

	# release:prepare builds and tests the whole reactor, native image
	# included, against the rewritten poms; the arguments reach that build.
	local arguments="-ntp"
	case "$tests" in
		all) step "Building, testing and preparing release $version" ;;
		none) step "Building and preparing release $version, nothing tested"; arguments="$arguments -DskipTests" ;;
	esac

	local -a prepare=(mvn -B -ntp release:clean release:prepare "-Darguments=$arguments"
		"-DdryRun=$([ "$dry_run" = yes ] && echo true || echo false)" "-DdevelopmentVersion=$next-SNAPSHOT")
	"${prepare[@]}" || die "release:prepare failed; 'mvn release:rollback' undoes what it got done."
	mvn -q -ntp release:clean

	if [ "$dry_run" = yes ]; then
		printf '\nDry run: nothing was committed, tagged or pushed.\n'
		return
	fi

	git rev-parse -q --verify "refs/tags/$tag" >/dev/null || die "release:prepare finished without creating $tag."

	step "Pushing master and $tag"
	git push origin master
	git push origin "$tag"

	printf '\n\033[1mCut %s\033[0m\n' "$version"
	printf '  tag       %s\n' "$tag"
	printf '  next      %s\n' "$(pom_version)"
	printf '  workflow  %s/actions/workflows/%s\n' "$(gh repo view --json url --jq .url)" "$WORKFLOW"
	printf '\nOnce the run has drafted the release, publish it with:\n'
	printf '  ./release.sh publish --tag %s --bootstrap <signed bootstrap.dat> --codename "<Name>"\n' "$tag"
}

dev_release() {
	local tests=all

	while [ $# -gt 0 ]; do
		case "$1" in
			--skip-tests) tests=none ;;
			*) die "Unknown option for dev: $1" ;;
		esac
		shift
	done

	cd "$ROOT"
	require_clean_master
	require_gh
	require_signing_key >/dev/null

	local version dev tag
	version="$(upcoming_version)"
	dev="$version-dev.$(next_dev_number "$version")"
	tag="v$dev"

	confirm "Tag dev release $dev and push $tag?"

	# The dev version lives only on the tagged commit, which the tag keeps
	# reachable, so master never carries it.
	local -a build=(mvn -B -ntp -pl common,application clean verify)
	[ "$tests" = all ] || build+=(-DskipTests)
	step "Building $dev off master"
	git switch -q --detach
	if ! mvn -q -ntp versions:set -DnewVersion="$dev" -DgenerateBackupPoms=false || ! "${build[@]}"; then
		git checkout -q -- .
		git switch -q master
		die "Building $dev failed; master is untouched."
	fi

	git commit -q -am "Dev release $dev"
	git tag -a "$tag" -m "Hedgehog $dev"
	git switch -q master

	step "Pushing $tag"
	git push origin "$tag"

	printf '\n\033[1mTagged %s\033[0m\n' "$dev"
	printf '  workflow  %s/actions/workflows/%s\n' "$(gh repo view --json url --jq .url)" "$WORKFLOW"
	printf '\nOnce the run has drafted the prerelease, publish it with:\n'
	printf '  ./release.sh publish --tag %s\n' "$tag"
}

await_draft() {
	local tag="$1"

	if ! gh release view "$tag" --json isDraft >/dev/null 2>&1; then
		step "Waiting for the $WORKFLOW run of $tag"
		local run
		run="$(gh run list --workflow "$WORKFLOW" --branch "$tag" --limit 1 \
			--json databaseId --jq '.[0].databaseId')"
		[ -n "$run" ] || die "No $WORKFLOW run for $tag yet. Has the tag been pushed?"
		gh run watch "$run" --exit-status \
			|| die "The $WORKFLOW run for $tag failed: $(gh run view "$run" --json url --jq .url)"
	fi

	local draft
	draft="$(gh release view "$tag" --json isDraft --jq .isDraft 2>/dev/null)" \
		|| die "The run finished, but no release for $tag exists."
	[ "$draft" = true ] || die "Release $tag is already published; nothing to do."
}

# The explicit file wins, then whatever an earlier attempt already attached,
# then the previous release's asset: fetch resolves the latest release, so a
# release without a bootstrap would break every node bootstrapping from it.
prepare_bootstrap() {
	local given="$1" assets="$2"
	local target="$assets/bootstrap.dat.gz"

	if [ -n "$given" ]; then
		case "$given" in
			*.gz) cp "$given" "$target" ;;
			*) gzip -9 -c "$given" > "$target" ;;
		esac
	elif [ -f "$target" ]; then
		printf 'Using the bootstrap already attached to the draft.\n'
	else
		local previous
		previous="$(gh api 'repos/{owner}/{repo}/releases/latest' --jq .tag_name 2>/dev/null)" \
			|| die "No --bootstrap given and no previous release to carry one over from."
		printf 'Carrying bootstrap.dat.gz over from %s.\n' "$previous"
		gh release download "$previous" --pattern bootstrap.dat.gz --dir "$assets" \
			|| die "$previous has no bootstrap.dat.gz; pass --bootstrap."
	fi
}

# The snapshot must verify against the keys compiled into the release being
# published, so the check runs through that release's own jar.
check_bootstrap() {
	local compressed="$1" java="$2" jar="$3"
	local raw="$SCRATCH/bootstrap.dat" status

	gzip -d -c "$compressed" > "$raw"
	status="$("$java" -jar "$jar" bootstrap info -s "$raw" | sed -n 's/^Signature: *//p')"
	[ "$status" = SIGNED ] \
		|| die "The bootstrap reports '${status:-no signature}' under the keys in this release; it must be SIGNED."
	rm -f "$raw"
}

publish_release() {
	local tag="" bootstrap="" codename="" notes=""

	while [ $# -gt 0 ]; do
		case "$1" in
			--tag) tag="${2:?--tag needs a tag}"; shift ;;
			--bootstrap) bootstrap="${2:?--bootstrap needs a file}"; shift ;;
			--codename) codename="${2:?--codename needs a name}"; shift ;;
			--notes-file) notes="${2:?--notes-file needs a file}"; shift ;;
			*) die "Unknown option for publish: $1" ;;
		esac
		shift
	done

	cd "$ROOT"
	require_gh
	[ -n "$tag" ] || tag="$(newest_tag)"
	local version="${tag#v}"
	is_version "$version" || is_dev_version "$version" \
		|| die "'$tag' is not a release tag of the form vX.Y.Z or vX.Y.Z-dev.N."
	is_version "$version" || [ -z "$codename" ] || die "A dev release carries no codename."
	[ -z "$bootstrap" ] || [ -f "$bootstrap" ] || die "No such bootstrap file: $bootstrap"
	[ -z "$notes" ] || [ -f "$notes" ] || die "No such notes file: $notes"

	local fingerprint java
	fingerprint="$(require_signing_key)"
	java="$(maven_java)"

	await_draft "$tag"

	local assets="$DIST/$tag"
	step "Downloading the drafted assets of $tag"
	rm -rf "$assets"
	mkdir -p "$assets"
	gh release download "$tag" --dir "$assets"

	local jar="$assets/hedgehog-$version-jar-with-dependencies.jar" expected
	for expected in "$jar" "$assets/hedgehog-$version-x86_64-linux-gnu.bin" \
		"$assets/hedgehog-$version-osx-arm64.bin" "$assets/hedgehog-$version-win64.exe"; do
		[ -f "$expected" ] || die "The draft is missing $(basename "$expected")."
	done

	step "Preparing bootstrap.dat.gz"
	prepare_bootstrap "$bootstrap" "$assets"
	check_bootstrap "$assets/bootstrap.dat.gz" "$java" "$jar"

	step "Signing every asset with $fingerprint"
	local file
	for file in "$assets"/*; do
		case "$file" in *.asc) continue ;; esac
		gpg --batch --yes --local-user "$fingerprint" --armor --detach-sign --output "$file.asc" "$file"
		gpg --verify "$file.asc" "$file" 2>/dev/null || die "The signature on $(basename "$file") does not verify."
		printf '  %s.asc\n' "$(basename "$file")"
	done

	step "Attaching the bootstrap and the signatures"
	gh release upload "$tag" --clobber "$assets/bootstrap.dat.gz" "$assets"/*.asc

	step "Publishing $tag"
	local title="$version"
	[ -z "$codename" ] || title="$version - $codename"
	# releases/latest is where snapshot builds fetch their bootstrap, so only
	# a real release may become it.
	local -a edit=(gh release edit "$tag" --draft=false --title "$title")
	if is_version "$version"; then
		edit+=(--latest)
	else
		edit+=(--prerelease --latest=false)
	fi
	[ -z "$notes" ] || edit+=(--notes-file "$notes")
	"${edit[@]}"

	printf '\n\033[1mPublished %s\033[0m\n' "$title"
	gh release view "$tag" --json url,assets --jq '.url, (.assets[] | "  \(.name)")'
	printf '\nAnyone can verify a download with:\n'
	printf '  gpg --import release-key.asc\n'
	printf '  gpg --verify <asset>.asc <asset>\n'
}

case "${1:-}" in
	cut) shift; cut_release "$@" ;;
	dev) shift; dev_release "$@" ;;
	publish) shift; publish_release "$@" ;;
	-h|--help|help) usage ;;
	"") usage >&2; exit 1 ;;
	*) die "Unknown command: $1 (try --help)" ;;
esac
