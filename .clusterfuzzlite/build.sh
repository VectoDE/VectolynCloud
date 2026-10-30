#!/bin/bash -eu
# Builds the ClusterFuzzLite fuzzers. Each *Fuzzer.java under .clusterfuzzlite/fuzzers is one
# target; it lives in the package of the class it fuzzes, because those classes are not public.
#
# The OSS-Fuzz JVM image runs on Java 17 while VectolynCloud builds for Java 21 with Gradle. These
# targets therefore compile the sources of the parsers they fuzz directly with --release 17. That
# works for the pure modules below; a target that needs a Java 21 API or a library must wait for
# an image with a newer JDK.

JACKSON_ANNOTATIONS=jackson-annotations-2.22.jar
JACKSON_ANNOTATIONS_SHA256=21ddb598807d3a51a876704eb979d9296e1c6a6f47ab1826ff88c6d6a127a2d0

cd "$SRC/VectolynCloud"

# Only annotations (cloud.config.model.Language); same version and checksum as
# gradle/verification-metadata.xml.
curl -fsSL -o "$JACKSON_ANNOTATIONS" \
  "https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-annotations/2.22/$JACKSON_ANNOTATIONS"
echo "$JACKSON_ANNOTATIONS_SHA256  $JACKSON_ANNOTATIONS" | sha256sum -c -

SOURCEPATH=""
for module in template console config backup database; do
  SOURCEPATH="$SOURCEPATH:cloud/$module/src/main/java"
done
SOURCEPATH="${SOURCEPATH#:}"

mkdir -p "$OUT/classes"
mapfile -t TARGETS < <(find .clusterfuzzlite/fuzzers -name '*Fuzzer.java')
javac --release 17 -encoding UTF-8 -proc:none -nowarn \
  -cp "$JAZZER_API_PATH:$JACKSON_ANNOTATIONS" \
  -sourcepath "$SOURCEPATH" \
  -d "$OUT/classes" \
  "${TARGETS[@]}"

cp "$JACKSON_ANNOTATIONS" "$OUT/"
cp /usr/local/bin/jazzer_agent_deploy.jar /usr/local/bin/jazzer_driver "$OUT/"

for target in "${TARGETS[@]}"; do
  package_path="${target#.clusterfuzzlite/fuzzers/}"
  class_name="$(echo "${package_path%.java}" | tr '/' '.')"
  wrapper="$OUT/$(basename "${package_path%.java}")"
  cat > "$wrapper" <<WRAPPER
#!/bin/sh
# LLVMFuzzerTestOneInput for fuzzer detection.
this_dir=\$(dirname "\$0")
LD_LIBRARY_PATH="$JVM_LD_LIBRARY_PATH":\$this_dir \
ASAN_OPTIONS=\$ASAN_OPTIONS:symbolize=1:external_symbolizer_path=\$this_dir/llvm-symbolizer:detect_leaks=0 \
\$this_dir/jazzer_driver --agent_path=\$this_dir/jazzer_agent_deploy.jar \
  --cp=\$this_dir/classes:\$this_dir/$JACKSON_ANNOTATIONS \
  --instrumentation_includes='net.vectolyn.**' \
  --target_class=$class_name \
  --jvm_args="-Xmx2048m" \
  \$@
WRAPPER
  chmod +x "$wrapper"
done
