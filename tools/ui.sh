#!/bin/bash
# Helpers for driving the app's UI on an emulator or device through the accessibility
# tree. Source this file. Set ANDROID_SERIAL first to choose the device.
#
#   texts                 all visible text, joined with " | "
#   descs                 all content descriptions
#   tap_text "Label" [n]  tap the n-th (0-based) node whose text or description is exactly Label
#   center "Label" [n]    print "x y" of that node (run dump first)
ADB="$HOME/Library/Android/sdk/platform-tools/adb"
TMP="${TMPDIR:-/tmp}/lumi-ui"
mkdir -p "$TMP"

dump() {
  "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  "$ADB" exec-out cat /sdcard/ui.xml > "$TMP/ui.xml"
}

center() {
  python3 - "$TMP/ui.xml" "$1" "${2:-0}" <<'EOF'
import re, sys, xml.etree.ElementTree as ET
path, want, nth = sys.argv[1], sys.argv[2], int(sys.argv[3])
root = ET.parse(path).getroot()
hits = [n for n in root.iter('node') if n.get('text') == want or n.get('content-desc') == want]
if len(hits) > nth:
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', hits[nth].get('bounds')))
    print((x1 + x2) // 2, (y1 + y2) // 2)
EOF
}

tap_text() {
  dump
  local xy; xy=$(center "$1" "${2:-0}")
  if [ -z "$xy" ]; then echo "  NOT FOUND: $1"; return 1; fi
  "$ADB" shell input tap $xy
  echo "  tapped '$1' at $xy"
}

texts() {
  dump
  python3 - "$TMP/ui.xml" <<'EOF'
import sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
print('  | '.join(n.get('text') for n in root.iter('node') if n.get('text')))
EOF
}

descs() {
  dump
  python3 - "$TMP/ui.xml" <<'EOF'
import sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
print('  | '.join(n.get('content-desc') for n in root.iter('node') if n.get('content-desc')))
EOF
}
