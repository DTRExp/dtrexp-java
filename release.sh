#!/usr/bin/env bash
# DTRExp Java — assemble the Maven Central bundle. javac/jar/javadoc only, no
# build tool. Reads the version from pom.xml, writes everything under the
# gitignored _tools/central/, and leaves a ready-to-upload zip:
#
#   ./release.sh          build main + sources + javadoc jars, sign each file
#                         with GPG, generate checksums, zip the bundle
#
# Upload the zip at https://central.sonatype.com → Publish Component.
# Requires a GPG signing key in the local keyring (prompts for the passphrase).
set -euo pipefail
cd "$(dirname "$0")"

V="$(sed -n 's|.*<version>\(.*\)</version>.*|\1|p' pom.xml | head -1)"
G_PATH="io/onury/dtrexp/$V"
STAGE="_tools/central/$G_PATH"
BUNDLE="_tools/central/dtrexp-$V-bundle.zip"

echo "version: $V"
rm -rf _tools/central out
mkdir -p "$STAGE" out/classes out/javadoc

# shellcheck disable=SC2046
javac --release 17 -Xlint:all -Werror -d out/classes $(find src -name '*.java')
javadoc --release 17 -quiet -Xdoclint:all,-missing -d out/javadoc \
    -sourcepath src io.onury.dtrexp

jar --create --file "$STAGE/dtrexp-$V.jar" -C out/classes . -C . LICENSE
jar --create --file "$STAGE/dtrexp-$V-sources.jar" -C src . -C . LICENSE
jar --create --file "$STAGE/dtrexp-$V-javadoc.jar" -C out/javadoc . -C . LICENSE
cp pom.xml "$STAGE/dtrexp-$V.pom"

for f in "$STAGE/dtrexp-$V.pom" "$STAGE"/*.jar; do
    gpg --armor --detach-sign --yes "$f"
    md5 -q "$f" > "$f.md5"
    shasum -a 1 "$f" | cut -d' ' -f1 > "$f.sha1"
done

(cd _tools/central && zip -qr "dtrexp-$V-bundle.zip" io)
echo "bundle: $BUNDLE"
unzip -l "$BUNDLE"
