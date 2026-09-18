#!/usr/bin/env bash
# =====================================================================
#  在 Linux 环境中安装并配置 Spark（适配已有 Hadoop 3.x 的虚拟机/服务器）
#
#  适用场景：机器上已装好 Hadoop（HDFS + YARN），需要补充安装 Spark。
#
#  执行方式（在项目根目录）：
#      chmod +x scripts/linux/*.sh
#      ./scripts/linux/setup-spark.sh                      # 免 sudo，装到 ~/module/spark
#      sudo -E ./scripts/linux/setup-spark.sh              # 装到 /opt/module/spark（注意 -E）
#      SPARK_HOME_DIR=/opt/spark ./scripts/linux/setup-spark.sh
#
#  脚本做了什么：
#      1. 自动定位 JAVA_HOME / HADOOP_HOME / HADOOP_CONF_DIR（多级兜底）
#      2. 选择可写目录，下载并解压 Spark（带 hadoop3 支持，Scala 2.12）
#      3. 校验下载包完整性（gzip -t），避免下到半截或错误页面
#      4. 生成 conf/spark-env.sh（导出 JAVA_HOME / HADOOP_CONF_DIR）
#      5. 配置环境变量（可写则写 /etc/profile.d，否则写 ~/.bashrc）
#      6. 用 spark-submit --version 自检，并检查 HADOOP_CONF_DIR 是否生效
#
#  可用环境变量覆盖：SPARK_VERSION / SPARK_HOME_DIR / DOWNLOAD_DIR
# =====================================================================
set -euo pipefail

SPARK_VERSION="${SPARK_VERSION:-3.5.9}"
SPARK_PKG="spark-${SPARK_VERSION}-bin-hadoop3"     # 必须用 Scala 2.12 版本，不是 -scala2.13
DOWNLOAD_DIR="${DOWNLOAD_DIR:-${HOME}/software}"

log()  { echo -e "\033[32m[setup-spark]\033[0m $*"; }
warn() { echo -e "\033[33m[setup-spark][WARN]\033[0m $*"; }
fail() { echo -e "\033[31m[setup-spark][ERROR]\033[0m $*"; exit 1; }

# ---------------------------------------------------------------------
# 1. 定位 JAVA_HOME（顺序：环境变量 → java 命令反推 → 常见安装目录）
# ---------------------------------------------------------------------
detect_java_home() {
  if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
    echo "${JAVA_HOME}"; return 0
  fi
  if command -v java >/dev/null 2>&1; then
    local jb; jb="$(readlink -f "$(command -v java)")"
    echo "$(dirname "$(dirname "${jb}")")"; return 0
  fi
  local d
  for d in /opt/module/jdk* /opt/jdk* /usr/lib/jvm/java-8-openjdk* /usr/lib/jvm/java-11-openjdk* /usr/lib/jvm/*; do
    if [ -x "${d}/bin/java" ]; then echo "${d}"; return 0; fi
  done
  echo ""
}

# ---------------------------------------------------------------------
# 2. 定位 HADOOP_HOME / HADOOP_CONF_DIR
# ---------------------------------------------------------------------
detect_hadoop_home() {
  if [ -n "${HADOOP_HOME:-}" ] && [ -d "${HADOOP_HOME}" ]; then
    echo "${HADOOP_HOME}"; return 0
  fi
  if command -v hadoop >/dev/null 2>&1; then
    local hb; hb="$(readlink -f "$(command -v hadoop)")"
    echo "$(dirname "$(dirname "${hb}")")"; return 0
  fi
  local d
  for d in /opt/module/hadoop* /opt/hadoop* /usr/local/hadoop*; do
    if [ -d "${d}/etc/hadoop" ]; then echo "${d}"; return 0; fi
  done
  echo ""
}

detect_hadoop_conf_dir() {
  if [ -n "${HADOOP_CONF_DIR:-}" ] && [ -d "${HADOOP_CONF_DIR}" ]; then
    echo "${HADOOP_CONF_DIR}"; return 0
  fi
  local hh; hh="$(detect_hadoop_home)"
  if [ -n "${hh}" ] && [ -d "${hh}/etc/hadoop" ]; then
    echo "${hh}/etc/hadoop"; return 0
  fi
  echo ""
}

JAVA_HOME_RESOLVED="$(detect_java_home)"
[ -n "${JAVA_HOME_RESOLVED}" ] || fail "未找到 JDK。请先安装 JDK 8/11，或导出 JAVA_HOME 后重试。"
HADOOP_HOME_RESOLVED="$(detect_hadoop_home)"
HADOOP_CONF_DIR_RESOLVED="$(detect_hadoop_conf_dir)"

# ---------------------------------------------------------------------
# 3. 选择可写的安装目录
# ---------------------------------------------------------------------
if [ -n "${SPARK_HOME_DIR:-}" ]; then
  INSTALL_DIR="${SPARK_HOME_DIR}"
elif [ -w /opt ] 2>/dev/null || [ "$(id -u)" = "0" ]; then
  INSTALL_DIR="/opt/module"
else
  INSTALL_DIR="${HOME}/module"
  warn "/opt 不可写，改为安装到 ${HOME}/module（想装到 /opt/module 请用 sudo -E 执行）"
fi
SPARK_HOME="${INSTALL_DIR}/spark"

log "检测到的环境"
log "  JAVA_HOME       = ${JAVA_HOME_RESOLVED}"
log "  HADOOP_HOME     = ${HADOOP_HOME_RESOLVED:-<未找到>}"
log "  HADOOP_CONF_DIR = ${HADOOP_CONF_DIR_RESOLVED:-<未找到>}"
log "  SPARK_HOME      = ${SPARK_HOME}"
log "  Spark 版本      = ${SPARK_VERSION}"

if [ -z "${HADOOP_CONF_DIR_RESOLVED}" ]; then
  warn "未找到 HADOOP_CONF_DIR：Spark 将无法识别 HDFS/YARN 地址。"
  warn "若只以 local[*] 模式读本地文件可先继续；要读 HDFS 必须补上这一项。"
fi

# ---------------------------------------------------------------------
# 4. 下载（多镜像兜底 + 完整性校验）
# ---------------------------------------------------------------------
MIRRORS=(
  "https://mirrors.tuna.tsinghua.edu.cn/apache/spark/spark-${SPARK_VERSION}/${SPARK_PKG}.tgz"
  "https://mirrors.aliyun.com/apache/spark/spark-${SPARK_VERSION}/${SPARK_PKG}.tgz"
  "https://mirrors.huaweicloud.com/apache/spark/spark-${SPARK_VERSION}/${SPARK_PKG}.tgz"
  "https://archive.apache.org/dist/spark/spark-${SPARK_VERSION}/${SPARK_PKG}.tgz"
)

mkdir -p "${DOWNLOAD_DIR}"
cd "${DOWNLOAD_DIR}"

if [ -f "${SPARK_PKG}.tgz" ] && gzip -t "${SPARK_PKG}.tgz" 2>/dev/null; then
  log "已存在完整安装包，跳过下载：${DOWNLOAD_DIR}/${SPARK_PKG}.tgz"
else
  rm -f "${SPARK_PKG}.tgz"
  downloaded=0
  for url in "${MIRRORS[@]}"; do
    log "尝试下载：${url}"
    if command -v curl >/dev/null 2>&1; then
      curl -fL --connect-timeout 20 --retry 2 -o "${SPARK_PKG}.tgz" "${url}" && downloaded=1 && break
    else
      wget -c -T 20 -O "${SPARK_PKG}.tgz" "${url}" && downloaded=1 && break
    fi
    warn "该源不可用，换下一个 ..."
  done
  [ "${downloaded}" -eq 1 ] || fail "所有镜像均下载失败。请手动下载 ${SPARK_PKG}.tgz 放到 ${DOWNLOAD_DIR} 后重跑本脚本。"
fi

# 完整性校验：下到 HTML 错误页或半截文件时 gzip -t 会失败
gzip -t "${SPARK_PKG}.tgz" 2>/dev/null || fail "${SPARK_PKG}.tgz 不是有效 gzip 包（可能下到错误页面或下载中断），请删除后重试。"
log "安装包校验通过，大小 $(du -h "${SPARK_PKG}.tgz" | cut -f1)"

# ---------------------------------------------------------------------
# 5. 解压安装
# ---------------------------------------------------------------------
mkdir -p "${INSTALL_DIR}"
if [ -d "${SPARK_HOME}" ]; then
  BACKUP="${SPARK_HOME}.bak.$(date +%Y%m%d%H%M%S)"
  warn "${SPARK_HOME} 已存在，备份为 ${BACKUP}"
  mv "${SPARK_HOME}" "${BACKUP}"
fi
log "解压到 ${SPARK_HOME} ..."
tar -zxf "${DOWNLOAD_DIR}/${SPARK_PKG}.tgz" -C "${INSTALL_DIR}"
mv "${INSTALL_DIR}/${SPARK_PKG}" "${SPARK_HOME}"

# ---------------------------------------------------------------------
# 6. 生成 spark-env.sh
# ---------------------------------------------------------------------
log "生成 conf/spark-env.sh ..."
{
  echo "#!/usr/bin/env bash"
  echo "# 由 setup-spark.sh 于 $(date '+%Y-%m-%d %H:%M:%S') 自动生成"
  echo "export JAVA_HOME=${JAVA_HOME_RESOLVED}"
  echo "export SPARK_HOME=${SPARK_HOME}"
  echo "export SPARK_CONF_DIR=${SPARK_HOME}/conf"
  if [ -n "${HADOOP_CONF_DIR_RESOLVED}" ]; then
    [ -n "${HADOOP_HOME_RESOLVED}" ] && echo "export HADOOP_HOME=${HADOOP_HOME_RESOLVED}"
    echo "export HADOOP_CONF_DIR=${HADOOP_CONF_DIR_RESOLVED}"
  fi
} > "${SPARK_HOME}/conf/spark-env.sh"
chmod +x "${SPARK_HOME}/conf/spark-env.sh"

# ---------------------------------------------------------------------
# 7. 配置环境变量（可写 /etc/profile.d 就写系统级，否则写 ~/.bashrc）
# ---------------------------------------------------------------------
if [ -w /etc/profile.d ] 2>/dev/null || [ "$(id -u)" = "0" ]; then
  ENV_FILE="/etc/profile.d/spark.sh"
  {
    echo "export SPARK_HOME=${SPARK_HOME}"
    echo 'export PATH=$PATH:$SPARK_HOME/bin:$SPARK_HOME/sbin'
  } > "${ENV_FILE}"
  chmod +x "${ENV_FILE}"
  log "已写入系统环境变量：${ENV_FILE}"
else
  ENV_FILE="${HOME}/.bashrc"
  if ! grep -q "SPARK_HOME=${SPARK_HOME}" "${ENV_FILE}" 2>/dev/null; then
    {
      echo ""
      echo "# >>> spark (由 setup-spark.sh 添加) >>>"
      echo "export SPARK_HOME=${SPARK_HOME}"
      echo 'export PATH=$PATH:$SPARK_HOME/bin:$SPARK_HOME/sbin'
      echo "# <<< spark <<<"
    } >> "${ENV_FILE}"
  fi
  log "已追加到 ${ENV_FILE}（当前终端请先执行： source ${ENV_FILE}）"
fi

export SPARK_HOME
export PATH="${PATH}:${SPARK_HOME}/bin:${SPARK_HOME}/sbin"
export JAVA_HOME="${JAVA_HOME_RESOLVED}"
if [ -n "${HADOOP_CONF_DIR_RESOLVED}" ]; then export HADOOP_CONF_DIR="${HADOOP_CONF_DIR_RESOLVED}"; fi

# ---------------------------------------------------------------------
# 8. 自检
# ---------------------------------------------------------------------
log "自检：spark-submit --version"
if "${SPARK_HOME}/bin/spark-submit" --version 2>&1 | head -5; then
  log "Spark 安装成功"
else
  fail "Spark 自检失败，请检查 ${SPARK_HOME}/conf/spark-env.sh 中的 JAVA_HOME"
fi

log "自检：能否识别 Hadoop 配置"
if [ -n "${HADOOP_CONF_DIR_RESOLVED}" ] && [ -f "${HADOOP_CONF_DIR_RESOLVED}/core-site.xml" ]; then
  FS_DEFAULT="$(grep -A2 'fs.defaultFS' "${HADOOP_CONF_DIR_RESOLVED}/core-site.xml" 2>/dev/null | grep -o '<value>[^<]*' | sed 's/<value>//' | head -1 || true)"
  log "  HADOOP_CONF_DIR 正常，fs.defaultFS = ${FS_DEFAULT:-<未读到，请检查 core-site.xml>}"
else
  warn "  未能确认 HADOOP_CONF_DIR，读 HDFS 时会报 UnknownHostException / No FileSystem"
fi

cat <<TIP

--------------------------------------------------------------------
安装完成。接下来：

1) 让环境变量生效（当前终端）
     source /etc/profile       # 若上面提示写的是 ~/.bashrc，则 source ~/.bashrc
     which spark-submit

2) 验证 Spark 能跑（可选，约 30 秒）
     spark-submit --version

3) 回到项目目录，按 docs/09 第 0.5 节继续：
     cd <项目目录>
     scripts/linux/upload-to-hdfs.sh     # 把数据传到 HDFS
     scripts/linux/run-etl-cluster.sh    # 默认 local[*]，跑离线作业

注意：本项目用 Scala 2.12 编译，必须使用 spark-${SPARK_VERSION}-bin-hadoop3.tgz
     （不要用 -bin-hadoop3-scala2.13 那个包）。
--------------------------------------------------------------------
TIP
