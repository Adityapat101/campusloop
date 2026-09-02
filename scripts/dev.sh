#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"

if [[ -z "${JAVA_HOME:-}" ]]; then
  if [[ -x /opt/homebrew/opt/openjdk@21/bin/java ]]; then
    export JAVA_HOME=/opt/homebrew/opt/openjdk@21
  elif [[ -x /usr/local/opt/openjdk@21/bin/java ]]; then
    export JAVA_HOME=/usr/local/opt/openjdk@21
  else
    echo "Java 21 is missing. Install it with: brew install openjdk@21" >&2
    exit 1
  fi
fi
export PATH="$JAVA_HOME/bin:$PATH"

if ! docker info >/dev/null 2>&1; then
  echo "Docker Desktop is not running. Open Docker Desktop, then try again." >&2
  exit 1
fi

docker compose up -d --wait
exec ./mvnw spring-boot:run
