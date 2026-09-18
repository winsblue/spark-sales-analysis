#!/usr/bin/env bash
# =====================================================================
#  在 Linux 大数据环境中提交 Spark 离线分析作业
#
#  默认使用 local[*]（Spark 装在 Linux 环境里、用满本机 CPU 核）：
#    - 本项目验证过的就是这条路径，代码零改动即可跑通
#    - 不需要 HDFS/YARN 协议与 Spark 版本完全匹配，风险最小
#  若集群规模足够且版本匹配，可改用 MASTER=yarn 真正提交到 YARN：
#    MASTER=yarn DEPLOY_MODE=client scripts/linux/run-etl-cluster.sh
#
#  执行前请确认：
#      1. Spark 已安装（scripts/linux/setup-spark.sh）
#      2. 若要读 HDFS：Hadoop 已启动，且已执行 upload-to-hdfs.sh
#      3. jdbc.url 指向 Linux 环境能访问到的 MySQL
#
#  执行方式（在项目根目录）：
#      scripts/linux/run-etl-cluster.sh                       # local[*]，两种计算方式
#      scripts/linux/run-etl-cluster.sh --mode=df             # 只跑 DataFrame 方式
#      MASTER=yarn scripts/linux/run-etl-cluster.sh           # 提交到 YARN
#      MASTER=spark://hadoop:7077 scripts/linux/run-etl-cluster.sh   # 独立集群模式
# =====================================================================
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SPARK_HOME="${SPARK_HOME:-/opt/module/spark}"
MASTER="${MASTER:-local[*]}"
DEPLOY_MODE="${DEPLOY_MODE:-client}"
JAR="${JAR:-${PROJECT_ROOT}/spark-job/target/spark-job-1.0.0-cluster.jar}"
CONF="${CONF:-${PROJECT_ROOT}/spark-job/src/main/resources/spark-job.properties}"

log()  { echo -e "\033[32m[run-etl-cluster]\033[0m $*"; }
warn() { echo -e "\033[33m[run-etl-cluster][WARN]\033[0m $*"; }
fail() { echo -e "\033[31m[run-etl-cluster][ERROR]\033[0m $*"; exit 1; }

[ -x "${SPARK_HOME}/bin/spark-submit" ] || fail "未找到 ${SPARK_HOME}/bin/spark-submit，请先执行 scripts/linux/setup-spark.sh"
[ -f "${CONF}" ] || fail "配置文件不存在：${CONF}"

if [ ! -f "${JAR}" ]; then
  log "未找到集群提交包，尝试使用 Maven 构建 ..."
  command -v mvn >/dev/null 2>&1 || fail "未安装 Maven，无法自动构建；也可在有 Maven 的机器上构建后 scp 到本机"
  (cd "${PROJECT_ROOT}" && mvn -B -pl spark-job -am -Pcluster-package clean package -DskipTests)
fi
[ -f "${JAR}" ] || fail "构建后仍未找到 ${JAR}"

# ---------------------------------------------------------------------
# 关键：把配置同步到"当前工作目录"
# local[*] / client 模式下 Driver 就是 spark-submit 这个 JVM，
# spark.driver.extraJavaOptions 不生效 → 无法用 -D 传外部配置。
# 所以作业改为从工作目录读取 ./spark-job.properties（优先级高于 jar 内置默认值）。
# ---------------------------------------------------------------------
cd "${PROJECT_ROOT}"
cp -f "${CONF}" "${PROJECT_ROOT}/spark-job.properties"
RAW_DIR="$(grep -E '^data.raw.dir=' "${PROJECT_ROOT}/spark-job.properties" | head -1 | cut -d= -f2-)"
log "已把配置同步到 ${PROJECT_ROOT}/spark-job.properties"
log "  当前 data.raw.dir = ${RAW_DIR}"
log "  当前 jdbc.url     = $(grep -E '^jdbc.url=' "${PROJECT_ROOT}/spark-job.properties" | head -1 | cut -d= -f2-)"
log "  如需修改：直接编辑该文件后重新执行本脚本即可"

# 数据源是不是远端文件系统？不是的话给个明确提醒，避免"跑通了但没用上 HDFS"这种无声偏差
case "${RAW_DIR}" in
  hdfs://*|viewfs://*|s3a://*|s3://*|oss://*) ;;
  *)
    warn "data.raw.dir=${RAW_DIR} 不是 HDFS 路径。"
    warn "  → 作业会读取本机 ${RAW_DIR} 目录下的 csv（能跑通，但没用到 HDFS）。"
    warn "  → 想走 HDFS，请在 ${CONF} 中改成："
    warn "      data.raw.dir=$(hdfs getconf -confKey fs.defaultFS 2>/dev/null || echo 'hdfs://<namenode>:8020')/sales/raw"
    warn "  → 改完重新执行本脚本即可（脚本每次都会把配置同步到工作目录）。"
    ;;
esac

# 只有 yarn / standalone / spark:// 支持 --deploy-mode，local 模式传了会直接报错
EXTRA_ARGS=()
case "${MASTER}" in
  yarn|standalone|spark://*)
    EXTRA_ARGS+=( --deploy-mode "${DEPLOY_MODE}" )
    ;;
  *)
    log "MASTER=${MASTER} 为本地模式，不传 --deploy-mode"
    ;;
esac

# shuffle 分区数：本地模式按 CPU 核数即可；集群模式建议 executor 核数 × 2~3
SHUFFLE_PARTITIONS="${SHUFFLE_PARTITIONS:-4}"

log "Spark 提交参数"
log "  SPARK_HOME  = ${SPARK_HOME}"
log "  MASTER      = ${MASTER}"
log "  JAR         = ${JAR}"
log "  CONF        = ${CONF}"
log "  业务参数    = $*"

# 说明：配置靠"工作目录下的 spark-job.properties"生效（见上面的 cp）。
# 下面两行的 -Dconfig.file 只在"Driver 独立启动"的场景（YARN cluster 模式）有用，
# 此时 Driver 拿到的路径必须是 --files 分发后的文件名。
# local[*] / client 模式下这两行会被 Spark 忽略（不会报错），配置仍由工作目录那一份生效。

"${SPARK_HOME}/bin/spark-submit" \
  --class com.sales.SalesAnalysisApplication \
  --master "${MASTER}" \
  "${EXTRA_ARGS[@]}" \
  --name spark-sales-analysis \
  --files "${CONF}" \
  --conf spark.driver.extraJavaOptions="-Dconfig.file=${CONF}" \
  --conf spark.executor.extraJavaOptions="-Dconfig.file=${CONF}" \
  --conf spark.sql.shuffle.partitions="${SHUFFLE_PARTITIONS}" \
  --conf spark.driver.memory=1g \
  --conf spark.executor.memory=2g \
  --conf spark.executor.cores=2 \
  --conf spark.yarn.maxAppAttempts=1 \
  "${JAR}" "$@"

cat <<TIP

--------------------------------------------------------------------
作业执行完毕。可通过以下方式查看结果：

1) HDFS / YARN 应用日志：
     yarn logs -applicationId <application_xxx>

2) 结果库中确认指标：
     mysql -h <mysql-host> -uroot -p sales_analysis \\
       -e "SELECT kpi_name, kpi_value, kpi_unit FROM ads_overview;"

3) 查看两种计算方式性能对比：
     mysql -h <mysql-host> -uroot -p sales_analysis \\
       -e "SELECT compute_mode, duration_ms, input_rows FROM etl_job_log;"
--------------------------------------------------------------------
TIP
