#!/usr/bin/env bash
# =====================================================================
#  在 Linux 大数据环境中提交 Spark 离线分析作业（集群模式）
#
#  默认提交到 YARN（client 模式）。执行前请确认：
#      1. HDFS 与 YARN 已启动（hdfs 正常、yarn 正常）
#      2. 已执行 scripts/linux/upload-to-hdfs.sh 上传数据
#      3. spark-job.properties 中 data.raw.dir 指向 HDFS 路径，
#         jdbc.url 指向集群各节点都能访问的 MySQL
#
#  执行方式（在项目根目录）：
#      scripts/linux/run-etl-cluster.sh                # 覆盖 YARN，两种计算方式
#      scripts/linux/run-etl-cluster.sh --mode=df      # 只跑 DataFrame
#      MASTER=spark://node1:7077 scripts/linux/run-etl-cluster.sh   # 独立集群模式
# =====================================================================
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SPARK_HOME="${SPARK_HOME:-/opt/module/spark}"
MASTER="${MASTER:-yarn}"
DEPLOY_MODE="${DEPLOY_MODE:-client}"
JAR="${JAR:-${PROJECT_ROOT}/spark-job/target/spark-job-1.0.0-cluster.jar}"
CONF="${CONF:-${PROJECT_ROOT}/spark-job/src/main/resources/spark-job.properties}"

log()  { echo -e "\033[32m[run-etl-cluster]\033[0m $*"; }
fail() { echo -e "\033[31m[run-etl-cluster][ERROR]\033[0m $*"; exit 1; }

[ -x "${SPARK_HOME}/bin/spark-submit" ] || fail "未找到 ${SPARK_HOME}/bin/spark-submit，请先执行 scripts/linux/setup-spark.sh"
[ -f "${CONF}" ] || fail "配置文件不存在：${CONF}"

if [ ! -f "${JAR}" ]; then
  log "未找到集群提交包，尝试使用 Maven 构建 ..."
  command -v mvn >/dev/null 2>&1 || fail "未安装 Maven，无法自动构建；也可在有 Maven 的机器上构建后 scp 到本机"
  (cd "${PROJECT_ROOT}" && mvn -B -pl spark-job -am -Pcluster-package clean package -DskipTests)
fi
[ -f "${JAR}" ] || fail "构建后仍未找到 ${JAR}"

log "Spark 提交参数"
log "  MASTER      = ${MASTER}"
log "  DEPLOY_MODE = ${DEPLOY_MODE}"
log "  JAR         = ${JAR}"
log "  CONF        = ${CONF}"
log "  业务参数    = $*"

"${SPARK_HOME}/bin/spark-submit" \
  --class com.sales.SalesAnalysisApplication \
  --master "${MASTER}" \
  --deploy-mode "${DEPLOY_MODE}" \
  --name spark-sales-analysis \
  --files "${CONF}" \
  --conf spark.driver.extraJavaOptions="-Dconfig.file=spark-job.properties" \
  --conf spark.executor.extraJavaOptions="-Dconfig.file=spark-job.properties" \
  --conf spark.sql.shuffle.partitions=12 \
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
