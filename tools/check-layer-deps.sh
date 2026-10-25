#!/usr/bin/env bash
# check-layer-deps.sh —— 分层依赖方向自动校验
# 用法：在项目根目录执行，或接入 CI 的 pre-build step。
# 返回非零码表示发现违例。

set -euo pipefail
SRC="app/src/main/java/com/radio/chinese"
VIOLATIONS=0

fail() {
    echo "❌ VIOLATION: $1"
    VIOLATIONS=$((VIOLATIONS + 1))
}

# 规则 1: domain/ 不得 import service/ 或 ui/（BuildConfig 例外）
while IFS= read -r line; do
    fail "domain → service/ui import: $line"
done < <(grep -rn "^import com\.radio\.chinese\.\(service\|ui\)\." "$SRC/domain/" 2>/dev/null || true)

# 规则 2: data/ 不得 import ui/（AppTheme 例外，待迁移后移除）
while IFS= read -r line; do
    if echo "$line" | grep -qv "AppTheme"; then
        fail "data → ui import: $line"
    fi
done < <(grep -rn "^import com\.radio\.chinese\.ui\." "$SRC/data/" 2>/dev/null || true)

# 规则 3: domain/ 不得 import data/
while IFS= read -r line; do
    fail "domain → data import: $line"
done < <(grep -rn "^import com\.radio\.chinese\.data\." "$SRC/domain/" 2>/dev/null || true)

# 规则 4: service/ 不得 import ui/（BuildConfig / 纯常量除外）
while IFS= read -r line; do
    fail "service → ui import: $line"
done < <(grep -rn "^import com\.radio\.chinese\.ui\." "$SRC/service/" 2>/dev/null || true)

if [ "$VIOLATIONS" -eq 0 ]; then
    echo "✅ 分层依赖检查通过"
    exit 0
else
    echo ""
    echo "⚠️  发现 $VIOLATIONS 处违例。请修正或通过接口/类型下移解除耦合。"
    exit 1
fi
