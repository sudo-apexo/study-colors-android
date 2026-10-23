#!/bin/sh
# Copies the newest page and the sign-in files from the desktop repo (study-colors) into this app and
# adds back the one line that loads the Android helper.
#
# Usage, from this folder:  ./sync-from-desktop.sh [path/to/study-colors/index.html]
set -e
SRC="${1:-../study-colors/index.html}"
DEST="app/src/main/assets/index.html"

if [ ! -f "$SRC" ]; then
  echo "Cannot find $SRC"
  echo "Pass the path to the desktop index.html as the first argument."
  exit 1
fi

DIR="$(dirname "$SRC")"
cp "$SRC" "$DEST"
# Sign-in code that the page loads next to itself. The Firebase settings (firebase-config.js) are NOT copied:
# the GitHub build writes them from the FIREBASE_CONFIG_JS secret, so they never sit in this repository.
for f in study-sync.js; do
  if [ ! -f "$DIR/$f" ]; then echo "Cannot find $DIR/$f"; exit 1; fi
  cp "$DIR/$f" "app/src/main/assets/$f"
done
sed -i 's#<meta name="viewport" content="width=device-width,initial-scale=1">#&\n<script src="android-shim.js"></script>#' "$DEST"

if [ "$(grep -c 'android-shim.js' "$DEST")" != "1" ]; then
  echo "Could not add the Android helper line. Check $DEST"
  exit 1
fi
echo "Done. Remember to raise versionName in app/build.gradle, then commit and push."
