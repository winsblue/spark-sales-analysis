#!/usr/bin/env bash
# =====================================================================
#  把本地生成的模拟数据上传到 HDFS，供集群模式的 Spark 作业读取。
#
#  执行方式（在项目根目录）：
#      scripts/linux/upload-to-hdfs.sh
#      HDFS_BASE_DIR=/user/warehouse/sales/raw scripts/linux/upload-to-hdfs.sh
# =====================================================================
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LOCAL_DIR="${LOCAL_DIR:-${PROJECT_ROOT}/data/raw}"
HDFS_BASE_DIR="${HDFS_BASE_DIR:-/sales/raw}"

log()  { echo -e "\033[32m[upload-to-hdfs]\033[0m $*"; }
warn() { echo -e "\033[33m[upload-to-hdfs][WARN]\033[0m $*"; }
fail() { echo -e "\033[31m[upload-to-hdfs][ERROR]\033[0m $*"; exit 1; }

command -v hdfs >/dev/null 2>&1 || fail "未找到 hdfs 命令，请先配置 HADOOP_HOME 并 source /etc/profile"

# ---------------------------------------------------------------------
# 安全模式检查
# NameNode 刚启动时会进入「安全模式」：此时文件系统只读，mkdir / put 一律失败，
# 报错形如 "Cannot create directory /sales/raw. Name node is in safe mode."
# 正常情况等 30 秒左右会自动退出；若长时间不退出，多半是 DataNode 没上报数据块。
# ---------------------------------------------------------------------
if hdfs dfsadmin -safemode get 2>/dev/null | grep -qi "ON"; then
  warn "NameNode 处于安全模式（HDFS 只读），等待其自动退出（最多 90 秒）..."
  warn "  安全模式是 NameNode 刚启动时的自保护状态：等 DataNode 上报完数据块才会自动解除。"
  if timeout 90 hdfs dfsadmin -safemode wait 2>/dev/null; then
    log "已退出安全模式，继续执行"
  else
    if hdfs dfsadmin -safemode get 2>/dev/null | grep -qi "ON"; then
      echo ""
      warn "90 秒后仍处于安全模式，通常是下面两种原因之一："
      warn "  A) DataNode 没起来 → 执行 jps，应看到 DataNode 进程；"
      warn "     再用 hdfs dfsadmin -report 看 Live datanodes 是否为 0"
      warn "  B) 单节点却把副本数配成了 3（HDFS 永远凑不齐副本，安全模式不会退出）"
      warn "     检查 \$HADOOP_CONF_DIR/hdfs-site.xml 里的 dfs.replication 是否为 1"
      warn ""
      warn "临时解法（开发环境无重要数据时可直接用）："
      warn "     hdfs dfsadmin -safemode leave"
      fail "请先处理安全模式问题，再重新执行本脚本。"
    fi
    log "已退出安全模式，继续执行"
  fi
fi

[ -d "${LOCAL_DIR}" ] || fail "本地数据目录不存在：${LOCAL_DIR}，请先生成数据"

ORDER_FILE="${LOCAL_DIR}/ods_order_detail.csv"
PRODUCT_FILE="${LOCAL_DIR}/ods_product.csv"
[ -f "${ORDER_FILE}" ]   || fail "缺少订单明细文件：${ORDER_FILE}"
[ -f "${PRODUCT_FILE}" ] || fail "缺少商品维度文件：${PRODUCT_FILE}"

log "本地数据目录 : ${LOCAL_DIR}"
log "HDFS 目标目录: ${HDFS_BASE_DIR}"
echo "订单明细行数 : $(($(wc -l < "${ORDER_FILE}") - 1))"
echo "商品维度行数 : $(($(wc -l < "${PRODUCT_FILE}") - 1))"

log "创建 HDFS 目录 ..."
hdfs dfs -mkdir -p "${HDFS_BASE_DIR}"

log "上传数据（覆盖模式）..."
hdfs dfs -put -f "${ORDER_FILE}"   "${HDFS_BASE_DIR}/"
hdfs dfs -put -f "${PRODUCT_FILE}" "${HDFS_BASE_DIR}/"

log "HDFS 文件列表："
hdfs dfs -ls "${HDFS_BASE_DIR}"

cat <<TIP

--------------------------------------------------------------------
上传完成。请确认 spark-job/src/main/resources/spark-job.properties 中：

    data.raw.dir=hdfs://<namenode-host>:8020${HDFS_BASE_DIR}

（端口按实际 fs.defaultFS 配置填写，可用 hdfs getconf -confKey fs.defaultFS 查看）
--------------------------------------------------------------------
TIP
