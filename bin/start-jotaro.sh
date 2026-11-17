#!/usr/bin/env zsh
set -euo pipefail

SCRIPT_DIR="${0:A:h}"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
TARGET_JAR="$REPO_DIR/agent/target/jotaro-agent-0.0.1-SNAPSHOT.jar"
LOCAL_JAR="$SCRIPT_DIR/jotaro-agent.jar"
INSTALLED_JAR="$HOME/.jotaro/bin/jotaro-agent.jar"

JAR_PATH=""
for candidate in "$TARGET_JAR" "$LOCAL_JAR" "$INSTALLED_JAR"; do
  if [[ -f "$candidate" ]]; then
    if [[ -z "$JAR_PATH" || "$candidate" -nt "$JAR_PATH" ]]; then
      JAR_PATH="$candidate"
    fi
  fi
done

if [[ -z "$JAR_PATH" ]]; then
  echo "未找到打包好的 Jotaro Agent jar，请先在项目根目录执行打包命令：" >&2
  echo "  ./package-agent.sh" >&2
  exit 1
fi

MODE="tui"
FORWARD_ARGS=()
for arg in "$@"; do
  case "$arg" in
    --web|--server)
      MODE="web"
      ;;
    --tui)
      MODE="tui"
      ;;
    *)
      FORWARD_ARGS+=("$arg")
      ;;
  esac
done

if [[ "$MODE" == "web" ]]; then
  exec java -jar "$JAR_PATH" --jotaro.mode=server "${FORWARD_ARGS[@]}"
else
  exec java -jar "$JAR_PATH" --tui "${FORWARD_ARGS[@]}"
fi
