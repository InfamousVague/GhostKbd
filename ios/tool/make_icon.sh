#!/bin/sh
# Makes every size in the iOS app icon set from one square picture with no transparency (the ghost over its keys,
# art/app-icon.png): ios/tool/make_icon.sh <picture.png>. iOS rounds the corners itself.
set -e
SRC="$1"
SET="$(dirname "$0")/../Runner/Assets.xcassets/AppIcon.appiconset"
for f in "$SET"/Icon-App-*.png; do
  n=$(basename "$f" .png); pt=${n#Icon-App-}; pt=${pt%%x*}; sc=${n##*@}; sc=${sc%x}
  px=$(echo "$pt * $sc" | bc | sed 's/\..*//')
  sips -s format png -z "$px" "$px" "$SRC" --out "$f" >/dev/null
done
