#!/usr/bin/env bash
#
# 增量新鲜度回归（嵌套 embed 扁平聚合）
#
# 验证目标：
#   1) 非根模块（直接子模块与深层节点）的代码/资源修改，在不 clean 的增量构建中
#      必须进入根模块的最终 fat AAR；
#   2) 移除这些内容后，再次增量构建必须让它们从产物中消失（防陈旧内容残留）。
#
# 为什么需要它：扁平聚合后，根模块直接从各节点**自身产物**取内容，链路上任何一环
# 断掉（explode 不重跑 / 注入目录残留 / reBundle 未重建）都会表现为「改了不生效」
# 或「删了还在」。本仓库历史上多次出现同类问题。
#
# 用法：
#   cd example && bash verify-incremental-freshness.sh
# 失败时以非 0 退出，可直接用于 CI 门禁。
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

GRADLE="./gradlew"
if [ ! -x "$GRADLE" ]; then
  GRADLE="./gradlew.bat"
fi

VARIANT="Flavor1Debug"
AAR="lib-main/build/outputs/fat-aar/flavor1Debug/lib-main-flavor1-debug.aar"
VERIFY=":lib-main:verifyNestedFatAar${VARIANT}"

DEEP_PKG="com/kezong/demo/libaar2"
DIRECT_PKG="com/kezong/demo/libaar"
DEEP_CODE_MARKER="FRESHNESS_PROBE_DEEP_9Z"
DIRECT_CODE_MARKER="FRESHNESS_PROBE_DIRECT_4X"
DEEP_RES_MARKER="lib-aar2-RESPROBE_7Q"
DIRECT_RES_MARKER="lib-aar-DIRECTPROBE_3K"

DEEP_CLASS="lib-aar2/src/main/java/${DEEP_PKG}/FreshnessProbe.java"
DEEP_RES="lib-aar2/src/main/res/values/freshness_probe.xml"
DIRECT_CLASS="lib-aar/src/main/java/${DIRECT_PKG}/FreshnessProbe.java"
DIRECT_RES="lib-aar/src/main/res/values/freshness_probe.xml"

cleanup() {
  rm -f "$DEEP_CLASS" "$DEEP_RES" "$DIRECT_CLASS" "$DIRECT_RES"
}
trap cleanup EXIT

fail() {
  echo "❌ $*" >&2
  exit 1
}

# aar 内某个条目的文本是否包含 needle（二进制安全）
entry_contains() {
  unzip -p "$AAR" "$1" 2>/dev/null | grep -aq "$2"
}

# aar 的 classes.jar 里某个 class 是否包含 needle
class_contains() {
  local classPath="$1" needle="$2" tmp rc
  tmp="$(mktemp)"
  unzip -p "$AAR" classes.jar > "$tmp" 2>/dev/null || { rm -f "$tmp"; return 1; }
  unzip -p "$tmp" "$classPath" 2>/dev/null | grep -aq "$needle"
  rc=$?
  rm -f "$tmp"
  return $rc
}

gradle_run() {
  "$GRADLE" "$@" --console=plain -q
}

assert_all_absent() {
  local phase="$1"
  class_contains "${DEEP_PKG}/FreshnessProbe.class" "$DEEP_CODE_MARKER" \
    && fail "${phase}：深层节点的探针类仍存在于 ${AAR}"
  class_contains "${DIRECT_PKG}/FreshnessProbe.class" "$DIRECT_CODE_MARKER" \
    && fail "${phase}：直接子模块的探针类仍存在于 ${AAR}"
  entry_contains "res/values/values.xml" "$DEEP_RES_MARKER" \
    && fail "${phase}：深层节点的探针资源仍存在于 ${AAR}"
  entry_contains "res/values/values.xml" "$DIRECT_RES_MARKER" \
    && fail "${phase}：直接子模块的探针资源仍存在于 ${AAR}"
  return 0
}

assert_all_present() {
  class_contains "${DEEP_PKG}/FreshnessProbe.class" "$DEEP_CODE_MARKER" \
    || fail "增量构建后：深层节点的代码修改没有进入 ${AAR}"
  class_contains "${DIRECT_PKG}/FreshnessProbe.class" "$DIRECT_CODE_MARKER" \
    || fail "增量构建后：直接子模块的代码修改没有进入 ${AAR}"
  entry_contains "res/values/values.xml" "$DEEP_RES_MARKER" \
    || fail "增量构建后：深层节点的资源修改没有进入 ${AAR}"
  entry_contains "res/values/values.xml" "$DIRECT_RES_MARKER" \
    || fail "增量构建后：直接子模块的资源修改没有进入 ${AAR}"
}

write_probes() {
  mkdir -p "$(dirname "$DEEP_CLASS")" "$(dirname "$DEEP_RES")" \
           "$(dirname "$DIRECT_CLASS")" "$(dirname "$DIRECT_RES")"
  cat > "$DEEP_CLASS" <<EOF
package com.kezong.demo.libaar2;

public class FreshnessProbe {
    public static final String MARKER = "${DEEP_CODE_MARKER}";
}
EOF
  cat > "$DIRECT_CLASS" <<EOF
package com.kezong.demo.libaar;

public class FreshnessProbe {
    public static final String MARKER = "${DIRECT_CODE_MARKER}";
}
EOF
  cat > "$DEEP_RES" <<EOF
<resources>
    <string name="freshness_probe_deep">${DEEP_RES_MARKER}</string>
</resources>
EOF
  cat > "$DIRECT_RES" <<EOF
<resources>
    <string name="freshness_probe_direct">${DIRECT_RES_MARKER}</string>
</resources>
EOF
}

echo "==> 1/3 基线：clean 构建并确认探针不存在"
cleanup
gradle_run clean "$VERIFY"
assert_all_absent "基线"

echo "==> 2/3 注入探针后，不 clean 增量构建，确认修改进入根产物"
write_probes
gradle_run "$VERIFY"
assert_all_present

echo "==> 3/3 移除探针后，不 clean 增量构建，确认内容从产物消失"
cleanup
gradle_run "$VERIFY"
assert_all_absent "移除后增量构建"

echo "✅ 增量新鲜度回归通过：非根模块的代码/资源修改双向（新增与移除）都能正确反映到根产物"
