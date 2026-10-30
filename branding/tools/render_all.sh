#!/bin/sh
# Renders all stills and the turntable with Blender Cycles into branding/tools/renders/.
# Usage: sh branding/tools/render_all.sh   (works from any directory)
#   BLENDER                  path to the Blender 5.2 executable (default: "blender" from PATH),
#                            e.g. BLENDER="C:/Program Files/Blender Foundation/Blender 5.2/blender.exe"
#   VECTOLYN_CYCLES_DEVICE   HIP (default, AMD), CUDA, OPTIX, METAL, ONEAPI or CPU (much slower)
HERE="$(cd "$(dirname "$0")" && pwd)"
B="${BLENDER:-blender}"
D="$HERE/renders"
if command -v cygpath >/dev/null 2>&1; then D="$(cygpath -m "$D")"; HERE="$(cygpath -m "$HERE")"; fi
OUT_FILTER="DEVICE|ERROR|Error|Traceback|DONE"
for j in social release readme_dark readme_light mark_alpha; do
  "$B" -b --factory-startup --python "$HERE/render_mark.py" -- $j "$D/final/$j.png" 2560 0 512 2>&1 | grep -E "$OUT_FILTER"
done
"$B" -b --factory-startup --python "$HERE/render_mark.py" -- hero "$D/final/hero.png" 3840 2160 512 2>&1 | grep -E "$OUT_FILTER"
"$B" -b --factory-startup --python "$HERE/render_mark.py" -- turntable "$D/tt/f_" 1920 1080 128 2>&1 | grep -E "$OUT_FILTER"
