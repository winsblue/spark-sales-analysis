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
fail() { echo -e "\033[31m[upload-to-hdfs][ERROR]\033[0m $*"; exit 1; }

command -v hdfs >/dev/null 2>&1 || fail "未找到 hdfs 命令，请先配置 HADOOP_HOME 并 source /etc/profile"

[ -d "${LOCAL_DIR}" ] || fail "本地数据目录不存在：${LOCAL_DIR}，请先执行 scripts/run-etl.cmd --generate 生成数据"

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
