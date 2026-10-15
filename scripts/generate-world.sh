#!/usr/bin/env bash
set -eu
WP_AI_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
exec java -Djava.awt.headless=true \
  -cp "$WP_AI_ROOT/WorldPainter/WPCore/target/classes:$WP_AI_ROOT/WorldPainter/WPCore/target/dependency/*" \
  org.pepsoft.worldpainter.ai.WorldGenerationCli "$@"
