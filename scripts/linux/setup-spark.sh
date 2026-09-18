#!/usr/bin/env bash
# =====================================================================
#  在 Linux 大数据环境中安装并配置 Spark（适配 Hadoop 3.x 集群）
#
#  适用场景：虚拟机 / 云服务器上已有 Hadoop（HDFS + YARN）环境，
#            需要补充安装 Spark 用于提交离线分析作业。
#
#  执行方式：
#      chmod +x setup-spark.sh
#      ./setup-spark.sh                # 默认安装到 /opt/module/spark
#      SPARK_HOME_DIR=/opt/spark ./setup-spark.sh
#
#  脚本做了什么：
#      1. 检查 java / hadoop 环境
#      2. 下载并解压 Spark（带 hadoop3 支持）
#      3. 生成 spark-env.sh（配置 JAVA_HOME / HADOOP_CONF_DIR）
#      4. 配置环境变量（写入 /etc/profile.d/spark.sh）
#      5. 用 spark-submit --version 做自检
# =====================================================================
set -euo pipefail

SPARK_VERSION="${SPARK_VERSION:-3.5.1}"
SPARK_PKG="spark-${SPARK_VERSION}-bin-hadoop3"
INSTALL_DIR="${SPARK_HOME_DIR:-/opt/module}"
SPARK_HOME="${INSTALL_DIR}/spark"
DOWNLOAD_DIR="${DOWNLOAD_DIR:-/opt/software}"

# 多个下载源，按顺序尝试（国内环境建议优先使用清华 / 阿里云镜像）
MIRRORS=(
  "https://mirrors.tuna.tsinghua.edu.cn/apache/spark/spark-${SPARK_VERSION}/${SPARK_PKG}.tgz"
  "https://mirrors.aliyun.com/apache/spark/spark-${SPARK_VERSION}/${SPARK_PKG}.tgz"
  "https://archive.apache.org/dist/spark/spark-${SPARK_VERSION}/${SPARK_PKG}.tgz"
)

log()  { echo -e "\033[32m[setup-spark]\033[0m $*"; }
warn() { echo -e "\033[33m[setup-spark][WARN]\033[0m $*"; }
fail() { echo -e "\033[31m[setup-spark][ERROR]\033[0m $*"; exit 1; }

# ---------- 1. 环境检查 ----------
log "检查运行环境 ..."
command -v java >/dev/null 2>&1 || fail "未检测到 java，请先安装 JDK 8 或 JDK 11"

if [ -z "${JAVA_HOME:-}" ]; then
  JAVA_BIN="$(readlink -f "$(command -v java)")"
  JAVA_HOME="$(dirname "$(dirname "$JAVA_BIN")")"
  warn "环境变量 JAVA_HOME 为空，自动推断为 ${JAVA_HOME}"
fi

if [ -z "${HADOOP_HOME:-}" ]; then
  warn "未设置 HADOOP_HOME。若要与 YARN/HDFS 集成，请先完成 Hadoop 环境配置。"
  warn "集群模式需要 HADOOP_CONF_DIR 指向 Hadoop 配置目录（如 /opt/module/hadoop/etc/hadoop）。"
fi

if [ -z "${HADOOP_CONF_DIR:-}" ] && [ -n "${HADOOP_HOME:-}" ]; then
  if [ -d "${HADOOP_HOME}/etc/hadoop" ]; then
    HADOOP_CONF_DIR="${HADOOP_HOME}/etc/hadoop"
    warn "环境变量 HADOOP_CONF_DIR 为空，自动推断为 ${HADOOP_CONF_DIR}"
  fi
fi

log "JAVA_HOME      = ${JAVA_HOME}"
log "HADOOP_HOME    = ${HADOOP_HOME:-<未设置>}"
log "HADOOP_CONF_DIR= ${HADOOP_CONF_DIR:-<未设置>}"
log "SPARK_HOME     = ${SPARK_HOME}"

# ---------- 2. 下载 Spark ----------
mkdir -p "${DOWNLOAD_DIR}"
cd "${DOWNLOAD_DIR}"

if [ ! -f "${SPARK_PKG}.tgz" ]; then
  downloaded=0
  for url in "${MIRRORS[@]}"; do
    log "尝试下载: ${url}"
    if command -v curl >/dev/null 2>&1; then
      curl -fL --connect-timeout 15 -o "${SPARK_PKG}.tgz" "${url}" && downloaded=1 && break
    else
      wget -c -T 15 -O "${SPARK_PKG}.tgz" "${url}" && downloaded=1 && break
    fi
    warn "该源不可用，尝试下一个 ..."
  done
  [ "${downloaded}" -eq 1 ] || fail "所有下载源均失败，请手动下载 ${SPARK_PKG}.tgz 到 ${DOWNLOAD_DIR}"
else
  log "已存在安装包，跳过下载"
fi

# ---------- 3. 解压安装 ----------
mkdir -p "${INSTALL_DIR}"
if [ -d "${SPARK_HOME}" ]; then
  warn "${SPARK_HOME} 已存在，将备份为 ${SPARK_HOME}.bak.$(date +%Y%m%d%H%M%S)"
  mv "${SPARK_HOME}" "${SPARK_HOME}.bak.$(date +%Y%m%d%H%M%S)"
fi
log "解压到 ${SPARK_HOME} ..."
tar -zxf "${DOWNLOAD_DIR}/${SPARK_PKG}.tgz" -C "${INSTALL_DIR}"
mv "${INSTALL_DIR}/${SPARK_PKG}" "${SPARK_HOME}"

# ---------- 4. 生成 spark-env.sh ----------
log "生成 spark-env.sh ..."
cat > "${SPARK_HOME}/conf/spark-env.sh" <<EOF
#!/usr/bin/env bash
# 由 setup-spark.sh 自动生成
export JAVA_HOME=${JAVA_HOME}
export SPARK_HOME=${SPARK_HOME}
export SPARK_CONF_DIR=${SPARK_HOME}/conf
EOF

if [ -n "${HADOOP_CONF_DIR:-}" ]; then
  cat >> "${SPARK_HOME}/conf/spark-env.sh" <<EOF
export HADOOP_HOME=${HADOOP_HOME}
export HADOOP_CONF_DIR=${HADOOP_CONF_DIR}
EOF
fi

cat >> "${SPARK_HOME}/conf/spark-env.sh" <<'EOF'
# 需要连接 Hive 元数据时可打开（本项目未使用）
# export SPARK_DIST_CLASSPATH=$(hadoop classpath):$SPARK_HOME/jars/*
EOF

chmod +x "${SPARK_HOME}/conf/spark-env.sh"

# ---------- 5. 配置环境变量 ----------
PROFILE_FILE="/etc/profile.d/spark.sh"
log "写入环境变量到 ${PROFILE_FILE} ..."
cat > "${PROFILE_FILE}" <<EOF
export SPARK_HOME=${SPARK_HOME}
export PATH=\$PATH:\$SPARK_HOME/bin:\$SPARK_HOME/sbin
EOF
chmod +x "${PROFILE_FILE}"
# shellcheck disable=SC1090
source "${PROFILE_FILE}"

# ---------- 6. 自检 ----------
log "执行自检 spark-submit --version ..."
if "${SPARK_HOME}/bin/spark-submit" --version >/dev/null 2>&1; then
  "${SPARK_HOME}/bin/spark-submit" --version
  log "Spark 安装成功。"
else
  fail "Spark 自检失败，请检查 JAVA_HOME 与 ${SPARK_HOME}/conf/spark-env.sh"
fi

cat <<'TIP'

--------------------------------------------------------------------
后续步骤：

1) 让环境变量立即生效：
     source /etc/profile

2) 把数据上传到 HDFS（在项目根目录执行）：
     scripts/linux/upload-to-hdfs.sh

3) 提交离线分析作业到 YARN：
     scripts/linux/run-etl-cluster.sh

4) 若使用 Spark 独立集群模式（非 YARN），可执行：
     $SPARK_HOME/sbin/start-all.sh
   然后把 spark-job.properties 中的 spark.master 设为：
     spark://<master-host>:7077
--------------------------------------------------------------------
TIP
