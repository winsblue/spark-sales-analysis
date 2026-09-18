#!/usr/bin/env bash
# =====================================================================
#  下载真实数据集（Olist 巴西电商公开数据集）到 data/real-raw/
#
#  用法（在项目根目录）：
#      chmod +x scripts/linux/*.sh
#      scripts/linux/download-real-data.sh
#      FORCE=1 scripts/linux/download-real-data.sh      # 强制重新下载
#
#  下载完成后转换为本项目 ODS 标准格式：
#      scripts/linux/run-etl-cluster.sh --import-real
#  然后把 spark-job.properties 的 data.raw.dir 指向 data/real，即可跑通全链路。
#
#  数据源：jsDelivr CDN（GitHub 公开数据集的镜像，国内可直连，无需代理）
# =====================================================================
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DEST_DIR="${PROJECT_ROOT}/data/real-raw"
BASE_URL="${BASE_URL:-https://cdn.jsdelivr.net/gh/spdrio/Brazilian-E-Commerce-Public-Dataset-by-Olist@master/files}"
FORCE="${FORCE:-0}"

log()  { echo -e "\033[32m[download-real-data]\033[0m $*"; }
warn() { echo -e "\033[33m[download-real-data][WARN]\033[0m $*"; }
fail() { echo -e "\033[31m[download-real-data][ERROR]\033[0m $*"; exit 1; }

# 文件名:最小字节数（下载后做完整性校验，防止存到错误页面）
FILES=(
  "olist_orders_dataset.csv:1000000"
  "olist_order_items_dataset.csv:5000000"
  "olist_customers_dataset.csv:5000000"
  "olist_products_dataset.csv:1000000"
  "olist_order_payments_dataset.csv:1000000"
  "product_category_name_translation.csv:1000"
)

mkdir -p "${DEST_DIR}"
log "目标目录: ${DEST_DIR}"

command -v curl >/dev/null 2>&1 || command -v wget >/dev/null 2>&1 \
  || fail "需要 curl 或 wget，请先安装"

failed=()
for entry in "${FILES[@]}"; do
  name="${entry%%:*}"
  min="${entry##*:}"
  out="${DEST_DIR}/${name}"

  if [ -f "${out}" ] && [ "$(stat -c%s "${out}" 2>/dev/null || echo 0)" -ge "${min}" ] && [ "${FORCE}" != "1" ]; then
    log "  已存在，跳过: ${name} ($(du -h "${out}" | cut -f1))"
    continue
  fi

  ok=0
  for attempt in 1 2 3 4; do
    log "  下载 ${name} （第 ${attempt} 次尝试）..."
    if command -v curl >/dev/null 2>&1; then
      curl -fL --connect-timeout 20 --retry 2 -A "Mozilla/5.0" \
        -o "${out}" "${BASE_URL}/${name}" && ok=1 && break
    else
      wget -c -T 30 -U "Mozilla/5.0" -O "${out}" "${BASE_URL}/${name}" && ok=1 && break
    fi
    warn "  第 ${attempt} 次失败，稍后重试 ..."
    sleep 4
  done

  if [ "${ok}" != "1" ]; then
    failed+=("${name}")
    continue
  fi

  size="$(stat -c%s "${out}" 2>/dev/null || echo 0)"
  if [ "${size}" -lt "${min}" ]; then
    warn "  ${name} 体积异常（${size} 字节 < 期望 ${min}），视为失败"
    failed+=("${name}")
  else
    log "  OK ${name}  $(du -h "${out}" | cut -f1)"
  fi
done

echo ""
if [ "${#failed[@]}" -gt 0 ]; then
  warn "以下文件下载失败：${failed[*]}"
  warn "请检查网络，或手动下载后放入 ${DEST_DIR}"
  exit 1
fi

log "全部下载完成，合计 $(du -sh "${DEST_DIR}" | cut -f1)"
cat <<TIP

--------------------------------------------------------------------
下一步：
  1) 转换成 ODS 标准格式：  scripts/linux/run-etl-cluster.sh --import-real
  2) 修改 spark-job.properties：
       data.raw.dir=data/real
  3) 跑通全链路：            scripts/linux/run-etl-cluster.sh --mode=both
--------------------------------------------------------------------
TIP
