#!/usr/bin/env zsh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo "==> 正在打包 jotaro-agent ..."
mvn -pl agent -am -DskipTests package

JAR_SRC="$SCRIPT_DIR/agent/target/jotaro-agent-0.0.1-SNAPSHOT.jar"
INSTALL_DIR="$HOME/.jotaro/bin"
mkdir -p "$INSTALL_DIR"

cp "$JAR_SRC" "$INSTALL_DIR/jotaro-agent.jar"
chmod +x "$SCRIPT_DIR/bin/start-jotaro.sh"
ln -sf "$SCRIPT_DIR/bin/start-jotaro.sh" "$INSTALL_DIR/start-jotaro"

ZSHRC="$HOME/.zshrc"
touch "$ZSHRC"
if ! grep -q "# >>> jotaro-agent start >>>" "$ZSHRC"; then
  cat >> "$ZSHRC" <<'EOF'

# >>> jotaro-agent start >>>
start() {
  if [[ "${1:-}" == "jotaro" ]]; then
    shift
    "$HOME/.jotaro/bin/start-jotaro" "$@"
  else
    command start "$@"
  fi
}
# <<< jotaro-agent start <<<
EOF
  echo "==> 已向 ~/.zshrc 注册 'start jotaro' 命令"
fi

echo "==> 打包完成！"
echo "    产物路径: $JAR_SRC"
echo "    安装路径: $INSTALL_DIR/jotaro-agent.jar"
echo "    启动命令: start jotaro        (默认 TUI 终端模式)"
echo "              start jotaro --web  (启动 8080 网页模式)"
