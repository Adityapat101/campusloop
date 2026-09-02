#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"

if [[ -z "${JAVA_HOME:-}" ]]; then
  if [[ -x /opt/homebrew/opt/openjdk@21/bin/java ]]; then
    export JAVA_HOME=/opt/homebrew/opt/openjdk@21
  elif [[ -x /usr/local/opt/openjdk@21/bin/java ]]; then
    export JAVA_HOME=/usr/local/opt/openjdk@21
  fi
fi
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$PATH"

./mvnw test
