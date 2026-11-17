#!/usr/bin/env zsh
set -euo pipefail

REPO_DIR="/Users/yuecaiheng.jotaro/study/pi-agent"
TARGET_JAR="$REPO_DIR/agent/target/jotaro-agent-0.0.1-SNAPSHOT.jar"
INSTALLED_JAR="$HOME/.jotaro/bin/jotaro-agent.jar"

JAR_PATH=""
if [[ -f "$TARGET_JAR" && -f "$INSTALLED_JAR" ]]; then
  if [[ "$TARGET_JAR" -nt "$INSTALLED_JAR" ]]; then
    JAR_PATH="$TARGET_JAR"
  else
    JAR_PATH="$INSTALLED_JAR"
  fi
elif [[ -f "$TARGET_JAR" ]]; then
  JAR_PATH="$TARGET_JAR"
elif [[ -f "$INSTALLED_JAR" ]]; then
  JAR_PATH="$INSTALLED_JAR"
else
  echo "未找到打包好的 Jotaro Agent jar，请先执行打包命令：" >&2
  echo "  $REPO_DIR/package-agent.sh" >&2
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
