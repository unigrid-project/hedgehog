#!/usr/bin/env bash
#
# Runs release.sh against a stand-in gh, a stand-in Maven and a throwaway
# signing key, and checks what it would have attached to a release. Nothing
# leaves the machine and no real key is touched. Usage: release-test.sh

set -euo pipefail

readonly SOURCE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly WORK="$(mktemp -d)"
readonly VERSION=0.0.9
readonly TAG="v$VERSION"
readonly REPO="$WORK/repo"
readonly BIN="$WORK/bin"
readonly REMOTE="$WORK/remote"
readonly UPLOADED="$WORK/uploaded"
failures=0
trap 'gpgconf --kill gpg-agent 2>/dev/null; rm -rf "$WORK"' EXIT

export GNUPGHOME="$WORK/gnupg" PATH="$BIN:$PATH" FAKE_REMOTE="$REMOTE" FAKE_UPLOADED="$UPLOADED"

check() {
	local description="$1"
	shift
	if "$@" >/dev/null 2>&1; then
		printf 'ok   %s\n' "$description"
	else
		printf 'FAIL %s\n' "$description"
		failures=$((failures + 1))
	fi
}

install_stand_ins() {
	mkdir -p "$BIN" "$WORK/jdk/bin" "$REPO"

	cat > "$BIN/gh" <<'EOF'
#!/usr/bin/env bash
case "$1 $2" in
	"auth status") ;;
	"release view") case "$*" in *isDraft*) echo "$FAKE_DRAFT" ;; esac ;;
	"release download") shift 3; [ "$1" = --dir ] && cp "$FAKE_REMOTE"/* "$2" ;;
	"release upload") for file in "${@:4}"; do [[ "$file" == --* ]] || cp "$file" "$FAKE_UPLOADED"; done ;;
	"release edit") ;;
	*) echo "unexpected gh $*" >&2; exit 1 ;;
esac
EOF
	printf '#!/usr/bin/env bash\nprintf %%s "%s"\n' "$WORK/jdk" > "$BIN/mvn"
	printf '#!/usr/bin/env bash\necho "Signature: SIGNED"\n' > "$WORK/jdk/bin/java"
	chmod +x "$BIN/gh" "$BIN/mvn" "$WORK/jdk/bin/java"

	cp "$SOURCE/release.sh" "$REPO/"
	mkdir -m 700 "$GNUPGHOME"
	gpg --batch --passphrase '' --quick-gen-key "Test Release <test@example.org>" ed25519 sign never 2>/dev/null
	gpg --armor --export > "$REPO/release-key.asc"
}

executables() {
	printf '%s\n' "hedgehog-$VERSION-jar-with-dependencies.jar" "hedgehog-$VERSION-x86_64-linux-gnu.bin" \
		"hedgehog-$VERSION-osx-arm64.bin" "hedgehog-$VERSION-win64.exe"
}

# What GitHub holds before the maintainer publishes: the executables alone.
stage_draft() {
	rm -rf "$REMOTE" "$UPLOADED"
	mkdir -p "$REMOTE" "$UPLOADED"
	local name
	while read -r name; do printf 'contents of %s\n' "$name" > "$REMOTE/$name"; done < <(executables)
	export FAKE_DRAFT=true
}

# What GitHub holds for a release published before the checksums existed.
stage_published() {
	stage_draft
	printf 'the snapshot\n' | gzip -9 -n > "$REMOTE/bootstrap.dat.gz"
	(cd "$REMOTE" && sha256sum bootstrap.dat.gz > bootstrap.dat.gz.sha256)
	local file
	for file in "$REMOTE"/*; do gpg --batch --armor --detach-sign --output "$file.asc" "$file"; done
	export FAKE_DRAFT=false
}

release() { (cd "$REPO" && ./release.sh "$@"); }

listed_names() { awk '{ print $2 }' "$UPLOADED/SHA256SUMS" | sort; }

expected_names() { { executables; echo bootstrap.dat.gz; } | sort; }

verifies_against_release() {
	gpg --verify "$UPLOADED/SHA256SUMS.asc" "$UPLOADED/SHA256SUMS" &&
		(cd "$REPO/dist/$TAG" && sha256sum -c "$UPLOADED/SHA256SUMS")
}

is_standard_format() { ! grep -qvE '^[0-9a-f]{64}  [^ ]+$' "$UPLOADED/SHA256SUMS"; }

all_signed() {
	local name
	for name in $(expected_names) bootstrap.dat.gz.sha256; do
		[ -f "$UPLOADED/$name.asc" ] || return 1
	done
}

nothing_uploaded() { [ -z "$(ls -A "$UPLOADED")" ]; }

install_stand_ins

stage_draft
echo 'the raw snapshot' > "$WORK/bootstrap.dat"
release publish --tag "$TAG" --bootstrap "$WORK/bootstrap.dat"
check "publish attaches SHA256SUMS" test -f "$UPLOADED/SHA256SUMS"
check "publish attaches SHA256SUMS.asc" test -f "$UPLOADED/SHA256SUMS.asc"
check "publish lists the executables, the jar and bootstrap.dat.gz" \
	test "$(listed_names)" = "$(expected_names)"
check "publish writes sha256sum format" is_standard_format
check "publish signs SHA256SUMS so that it verifies" verifies_against_release
check "publish still attaches the bootstrap hash and its signature" \
	test -f "$UPLOADED/bootstrap.dat.gz.sha256" -a -f "$UPLOADED/bootstrap.dat.gz.sha256.asc"
check "publish still signs every asset" all_signed

stage_published
release checksums --tag "$TAG"
check "checksums attaches SHA256SUMS and its signature only" \
	test "$(ls "$UPLOADED" | tr '\n' ' ')" = "SHA256SUMS SHA256SUMS.asc "
check "checksums lists the executables, the jar and bootstrap.dat.gz" \
	test "$(listed_names)" = "$(expected_names)"
check "checksums writes sha256sum format" is_standard_format
check "checksums signs SHA256SUMS so that it verifies" verifies_against_release

stage_published
echo tampered >> "$REMOTE/hedgehog-$VERSION-win64.exe"
check "checksums refuses an asset whose signature no longer verifies" \
	bash -c "! ( cd '$REPO' && ./release.sh checksums --tag $TAG )"
check "checksums attaches nothing after refusing" nothing_uploaded

stage_published
rm "$REMOTE/hedgehog-$VERSION-win64.exe.asc"
check "checksums refuses an asset without a signature" \
	bash -c "! ( cd '$REPO' && ./release.sh checksums --tag $TAG )"

stage_draft
check "checksums refuses a draft" bash -c "! ( cd '$REPO' && ./release.sh checksums --tag $TAG )"

[ "$failures" -eq 0 ] || { printf '\n%d check(s) failed\n' "$failures" >&2; exit 1; }
printf '\nAll checks passed\n'
