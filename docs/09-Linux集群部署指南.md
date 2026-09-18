# 九、Linux 大数据集群部署与提交指南

> 本文档说明如何把本项目部署到 **已有 Hadoop 环境** 的 Linux 虚拟机上运行。
> 项目同时支持两种运行环境，**只需改配置、无需改代码**：

| 运行环境 | 适用场景 | Spark 运行模式 | 数据源 | 结果库 |
| --- | --- | --- | --- | --- |
| Windows / Linux 单机 | 本地开发、逻辑验证、答辩演示 | `local[*]` | 本地文件 | 本机 MySQL |
| Linux 大数据集群 | 生产形态、体现集群处理能力 | YARN 或 Standalone | HDFS | 集群可访问的 MySQL |

---

## 0. 本机实测环境参数（2026-09-21 探明，可直接照抄）

> 本节是把虚拟机的真实参数探测出来后补上的。实际部署时**优先按本节取值**，后面章节仅作通用说明。

### 0.1 虚拟机配置

| 项目 | 实测值 | 获取方式 |
| --- | --- | --- |
| 虚拟机文件 | `D:\VMs\hadoop\hadoop.vmx` | 宿主机搜索 `*.vmx` |
| 显示名 / 系统 | `hadoop` / `ubuntu-64`（Ubuntu 64 位） | `.vmx` 中 `displayName`、`guestOS` |
| 配置 | **4 vCPU / 8 GB 内存** | `.vmx` 中 `numvcpus`、`memsize` |
| 网络类型 | **NAT**（VMnet8） | `.vmx` 中 `ethernet0.connectionType` |
| **虚拟机 IP** | **`192.168.10.128`** | VMware DHCP 租约 `C:\ProgramData\VMware\vmnetdhcp.leases` + `~/.ssh/known_hosts` |
| 虚拟机主机名 | `hadoop-VMware-Virtual-Platform` | DHCP 租约中 `client-hostname` |
| 宿主机 VMnet8 地址 | **`192.168.10.1`** | `Get-NetIPAddress` |

**节点数量：单节点（伪分布式）**
`192.168.10.100 / 102 / 104` 均不通，仅 `.128` 存活；`Documents\Virtual Machines` 下的
`hadoop1`、`hadoop2` 两个虚拟机文件时间是 2025-04 且带 `autoinst.iso`（安装过程残留），已废弃。
→ **按单节点伪分布式部署即可。**

### 0.2 已安装组件

| 组件 | 版本 | 判断依据 |
| --- | --- | --- |
| **Hadoop** | **3.1.3** | `D:\尚硅谷hadoop\资料\资料\04_jar包\hadoop-3.1.3.tar.gz`、`06_Windows依赖\hadoop-3.1.0`、`05_linux环境编译源码\hadoop-3.1.3-src.tar.gz` |
| JDK | **8u212** | `04_jar包\jdk-8u212-linux-x64.tar.gz`（与本项目 JDK 8 完全匹配） |
| 课程体系 | 尚硅谷 Hadoop 3.x（原始模板为 CentOS 7.5） | `资料\02_linux7.5镜像\CentOS-7.5-x86_64-DVD-1804.tmp` |
| **Spark** | **未安装** | 端口 7077（Master）/ 4040（UI）均不通 |
| **Hadoop 服务** | **当前未启动** | 端口 8088（YARN RM）/ 9870、50070（HDFS NN）均不通 |

> 部署前请先登录虚拟机启动集群：
> ```bash
> start-dfs.sh && start-yarn.sh
> jps     # 确认 NameNode / DataNode / ResourceManager / NodeManager 都在
> ```

### 0.3 结果库（MySQL）连通性 —— 已实测可用，宿主机零改动

| 检查项 | 实测结果 | 结论 |
| --- | --- | --- |
| MySQL 监听地址 | `0.0.0.0:3306` | 监听全部网卡，非仅本机 ✅ |
| 远程账号 | `root@%` 已存在 | 无需再执行 `GRANT` ✅ |
| 防火墙入站规则 | `MySQL Server`，TCP 3306，Allow | 已放行 ✅ |
| Windows 网络位置 | VMnet8 未分类 → 按 Public 处理 | 规则 Profile=Public 正好生效 ✅ |
| **远程连接实测** | 经 `192.168.10.1:3306` + `root/123456` 连接成功，读到 `dwd_order_detail` 共 193,061 行 | **VM 侧可直接写库** ✅ |

**因此虚拟机里 `spark-job.properties` 的数据库地址直接填：**

```properties
jdbc.url=jdbc:mysql://192.168.10.1:3306/sales_analysis?useUnicode=true&characterEncoding=utf8&useSSL=false
jdbc.user=root
jdbc.password=123456
```

> `192.168.10.1` 是宿主机在 VMnet8（NAT）上的地址，NAT 模式下虚拟机可直接访问。
> 若虚拟机内连不上，先在里面执行 `telnet 192.168.10.1 3306` 排查网络。

### 0.4 Spark 版本选择的重要提醒

本机 Hadoop 是 **3.1.3（2018 年版本）**，而 Spark 3.5.x 自带 `hadoop-client` 为 **3.3.4**，
属于"新客户端连旧服务端"，**上 YARN 存在兼容风险**（YARN ApplicationMaster 协议在 Hadoop 3.x 中变更过）。

按稳妥程度排序选择运行方式：

| 方案 | 做法 | 优点 | 风险 |
| --- | --- | --- | --- |
| **① 推荐** | Spark 装进虚拟机，用 `--master local[*]`；数据源走 **HDFS**，结果写宿主机 MySQL | 用上了 HDFS（体现大数据环境）；完全不碰 YARN 协议兼容性；4 vCPU/8GB 单节点跑 `local[*]` 性能最好 | 计算非分布式（但满足"基础—提高"要求） |
| ② 折中 | 同 ①，`data.raw.dir` 留本地路径 | 链路最短，最快跑通 | 没体现 HDFS |
| ③ 保守上 YARN | 降级到 Spark 3.1.x / 3.2.x（自带 hadoop-client 更接近 3.1.3） | 能真正跑分布式 | 需自行验证；单节点 YARN 资源紧张 |
| ④ 直接用现役 3.5.9 上 YARN | 不做版本调整 | 版本最新 | **可能 AM/Container 协议不兼容**，需调参 |

> 本项目在 Windows 上验证的就是 `local[*]` 路径（20 万行 36.6 秒完成），
> 所以**方案 ① 是把已验证代码原样搬到 Linux 的最短路径**，答辩演示最稳。
> 若指导教师明确要求"必须提交到 YARN"，再走方案 ③。

### 0.5 本机可直接照抄的操作序列（推荐方案 ①）

先把项目目录从宿主机拷进虚拟机（推荐用 Xftp，或虚拟机内直接 `git clone`），然后：

```bash
# ---------- ① 检查环境（先确认 Hadoop 3.1.3 与 JDK8 可用）----------
hadoop version | head -3
java -version
echo $HADOOP_HOME $HADOOP_CONF_DIR
start-dfs.sh && start-yarn.sh      # 若集群没起
jps
hdfs getconf -confKey fs.defaultFS  # 记下这个值，下一步要用

# ---------- ② 安装 Spark ----------
cd spark-sales-analysis
chmod +x scripts/linux/*.sh
./scripts/linux/setup-spark.sh
source /etc/profile
spark-submit --version

# ---------- ③ 验证虚拟机能否连上宿主机的 MySQL ----------
#   若这条能连上，后面作业写库就不会有问题
mysql -h 192.168.10.1 -uroot -p123456 -e "SELECT COUNT(*) FROM sales_analysis.dwd_order_detail;"

# ---------- ④ 生成模拟数据并上传到 HDFS ----------
mvn -B -pl spark-job -am -Pcluster-package clean package -DskipTests
java -cp spark-job/target/spark-job-1.0.0-cluster.jar com.sales.SalesAnalysisApplication --generate
export HDFS_BASE_DIR=/sales/raw
scripts/linux/upload-to-hdfs.sh

# ---------- ⑤ 改配置（三处，其它不动）----------
#   data.raw.dir = hdfs://192.168.10.128:8020/sales/raw     （用 ① 里 getconf 得到的值）
#   spark.master =                                          （留空）
#   jdbc.url     = jdbc:mysql://192.168.10.1:3306/sales_analysis?useUnicode=true&characterEncoding=utf8&useSSL=false
#   hadoop.home.dir =                                       （留空）

# ---------- ⑥ 提交作业（推荐 local[*]，稳；要先跳过 ODS 装载以省时间）----------
MASTER='local[*]' scripts/linux/run-etl-cluster.sh --mode=both --no-ods

# ---------- ⑦ 验证结果 ----------
mysql -h 192.168.10.1 -uroot -p123456 sales_analysis -e "
  SELECT kpi_name, kpi_value, kpi_unit FROM ads_overview ORDER BY id;
  SELECT compute_mode, duration_ms, input_rows FROM etl_job_log ORDER BY id;"
```

> 注意：`run-etl-cluster.sh` 的 `MASTER` 默认已是 **`local[*]`**（见 0.4 节方案 ①），
> 所以上面不需要额外设置 MASTER；要上 YARN 才显式写 `MASTER=yarn`。
> 若 `hdfs getconf -confKey fs.defaultFS` 返回的是 `file:///`，说明 `core-site.xml` 的
> `fs.defaultFS` 没配成 HDFS，需要先把 Hadoop 配好（这一步属于 Hadoop 环境问题，不在本项目范围内）。

---

## 1. 前置条件检查

在虚拟机上执行以下命令，确认 Hadoop 环境可用：

```bash
# 1) Java 环境
java -version                 # 需要 JDK 8 或 JDK 11
echo $JAVA_HOME

# 2) Hadoop 环境变量
echo $HADOOP_HOME
echo $HADOOP_CONF_DIR         # 一般为 $HADOOP_HOME/etc/hadoop

# 3) HDFS 是否可用
hdfs dfs -ls /                # 能列出目录说明 HDFS 正常
hdfs getconf -confKey fs.defaultFS     # 查看 NameNode 地址，如 hdfs://node1:8020

# 4) YARN 是否可用
yarn node -list               # 能列出 NodeManager 说明 YARN 正常

# 5) 查看 Hadoop / Hive 版本（决定 Spark 版本选择）
hadoop version
```

> 若上述命令报"未找到命令"，请先完成 Hadoop 环境变量配置：
> `source /etc/profile` 或检查 `/etc/profile.d/` 下的配置。

**环境变量参考（三节点集群示例）**

```bash
# /etc/profile.d/my_env.sh
export JAVA_HOME=/opt/module/jdk1.8.0_212
export HADOOP_HOME=/opt/module/hadoop-3.1.3
export HADOOP_CONF_DIR=$HADOOP_HOME/etc/hadoop
export PATH=$PATH:$JAVA_HOME/bin:$HADOOP_HOME/bin:$HADOOP_HOME/sbin
```

---

## 2. 安装 Spark

项目提供了自动安装脚本 `scripts/linux/setup-spark.sh`：

```bash
# 上传项目到虚拟机（或用 git clone），进入项目根目录
cd spark-sales-analysis

# 赋予执行权限
chmod +x scripts/linux/*.sh

# 安装 Spark（默认装到 /opt/module/spark，可通过环境变量覆盖）
./scripts/linux/setup-spark.sh

# 让环境变量生效
source /etc/profile
spark-submit --version
```

脚本会自动完成：

1. 检查 `java` / `hadoop` 环境，**多级兜底**自动推断 `JAVA_HOME` / `HADOOP_HOME` / `HADOOP_CONF_DIR`
   （环境变量 → `command -v java|hadoop` 反推 → 常见安装目录），因此 `sudo` 下环境变量丢失也能工作；
2. 依次尝试清华、阿里云、华为云、Apache 归档四个下载源获取 `spark-3.5.9-bin-hadoop3.tgz`（约 383 MB），
   下载后用 `gzip -t` 校验完整性（防止下到 HTML 错误页或半截文件）；
3. 解压到 `$SPARK_HOME`（已存在则先备份为 `.bak.<时间戳>`）；
4. 生成 `conf/spark-env.sh`，写入 `JAVA_HOME` / `HADOOP_CONF_DIR`；
5. 配置 `PATH`：`/etc/profile.d/spark.sh` 可写就写系统级，否则追加到 `~/.bashrc`；
6. 用 `spark-submit --version` 自检，并读出 `core-site.xml` 里的 `fs.defaultFS` 供你核对。

> **关于 sudo（两个坑，已规避但要理解）**
> - `sudo` 默认会重置环境变量，导致脚本读不到 `JAVA_HOME` / `HADOOP_CONF_DIR` → 早期版本会装出一个连不上 HDFS 的 Spark。
>   脚本已改为多级兜底，但仍建议：**能用 `sudo -E` 就用 `-E` 保留环境变量**。
> - 不需要 sudo 也能装：脚本检测到 `/opt` 不可写时会自动装到 `~/module/spark`，
>   并把 `PATH` 追加到 `~/.bashrc`。这对课程作业完全够用。

### 版本选择建议

| 集群 Hadoop 版本 | 建议 Spark 版本 | 说明 |
| --- | --- | --- |
| Hadoop 3.x | **Spark 3.5.9（bin-hadoop3）** | 当前 Apache 镜像上的现役版本，推荐 |
| Hadoop 2.7 ~ 2.10 | Spark 3.0~3.3（bin-hadoop2.7） | 用 `SPARK_VERSION=3.3.4` 覆盖脚本变量 |

> **重要：Apache 镜像只保留各分支的最新版**，旧版本会从 `mirrors.tuna` / `mirrors.aliyun` 下架
> （实测 `spark-3.5.1`、`3.5.3`、`3.5.6`、`3.5.7` 在镜像上均已 404，只剩 `3.5.8` / `3.5.9`）。
> 需要固定旧版本时，用 Apache 官方归档源：
> ```bash
> # 归档源保留所有历史版本，但国内访问较慢
> SPARK_VERSION=3.5.1 ./scripts/linux/setup-spark.sh
> # 脚本会依次尝试镜像，最后回落到 archive.apache.org
> ```
>
> **另一个容易踩的坑：Scala 版本必须匹配。**
> Spark 的二进制包有两个名字：
> - `spark-3.5.9-bin-hadoop3.tgz` → **Scala 2.12**（本项目用这个，pom 里是 `spark-sql_2.12`）
> - `spark-3.5.9-bin-hadoop3-scala2.13.tgz` → Scala 2.13（**下了会报 `NoSuchMethodError`**）
>
> 脚本已经固定下载 Scala 2.12 那个包，手动下载时请注意。
>
> **同理，本项目在 Windows 上用 3.5.1 验证通过，集群上用 3.5.8/3.5.9 也可以** ——
> 同一 3.5.x 分支内 API 与二进制兼容（`spark-sql_2.12` 的接口没有变化）。

### 如果虚拟机上没装 Maven

脚本本身只需要 Spark。但若要**在虚拟机上直接构建提交包**，需要 Maven：

```bash
mvn -version || sudo yum install -y maven      # CentOS / RHEL
mvn -version || sudo apt install -y maven      # Ubuntu / Debian
```

也可以在 Windows 上构建好 jar，再上传到虚拟机（见第 3 节）。

---

## 3. 准备提交包

### 方式 A：在虚拟机上构建（推荐，版本一致）

```bash
cd spark-sales-analysis
mvn -B -pl spark-job -am -Pcluster-package clean package -DskipTests
# 产物：spark-job/target/spark-job-1.0.0-cluster.jar
```

`cluster-package` 这个 profile 用**白名单**只打包「本项目代码 + MySQL 驱动」，
Spark / Scala / Hadoop / parquet / arrow 等全部由集群提供，jar 只有 **约 1 MB**。

> **踩坑记录：为什么用白名单而不是黑名单？**
> 因为 `spark-sql` 是 compile 作用域，它的传递依赖里除了 `org.apache.spark:*` 还有
> parquet / orc / arrow / netty / avro / zookeeper 等几十个包。早期版本用"排除 Apache Spark"
> 的黑名单写法，实测漏进来 **112 MB** 内容 —— 这些包 Spark 自带的 jars 里都有，
> 打进提交包会造成版本冲突（典型的症状是运行时报 `NoSuchMethodError`）。
> 改成白名单后 jar 从 112 MB 降到 1 MB，彻底消除该风险。

### 方式 B：在 Windows 上构建后上传

```powershell
# 在 Windows 项目根目录
D:\maven\apache-maven-3.8.8\bin\mvn.cmd -B -pl spark-job -am -Pcluster-package clean package -DskipTests
```

```bash
# 在虚拟机上接收（<user>@<vm-ip> 替换为实际值）
scp spark-job/target/spark-job-1.0.0-cluster.jar <user>@<vm-ip>:/opt/app/
scp spark-job/src/main/resources/spark-job.properties <user>@<vm-ip>:/opt/app/
scp sql/01_schema.sql <user>@<vm-ip>:/opt/app/
```

> 注意：Windows 与 Linux 换行符不同，仓库已通过 `.gitattributes` 保证 `.sh` 脚本为 LF，
> 若手工拷贝脚本后执行报 `bad interpreter`，可用 `sed -i 's/\r$//' scripts/linux/*.sh` 修正。

---

## 4. 生成数据并上传到 HDFS

数据生成依赖 `java.io`，只能在本地文件系统写入，因此**先本地生成、再上传 HDFS**：

```bash
# ① 生成模拟数据（约 20 万行，耗时约 10 秒）
java -cp spark-job/target/spark-job-1.0.0-cluster.jar:<spark jars> com.sales.SalesAnalysisApplication --generate
# 或者先 mvn -pl spark-job exec:java -Dexec.args="--generate"

# ② 上传到 HDFS（脚本自动创建目录并覆盖上传）
export HDFS_BASE_DIR=/sales/raw
scripts/linux/upload-to-hdfs.sh

# ③ 确认上传结果
hdfs dfs -ls -h /sales/raw
```

`upload-to-hdfs.sh` 会打印提示，告诉你 `data.raw.dir` 应该填什么。

---

## 5. 调整配置

编辑 `spark-job/src/main/resources/spark-job.properties`（或提交时用 `--conf` 覆盖）：

```properties
# ① 数据源改为 HDFS 路径（用第 4 步打印出的地址）
data.raw.dir=hdfs://192.168.10.128:8020/sales/raw

# ② Spark 运行模式留空 —— 由 spark-submit --master 决定
spark.master=

# ③ shuffle 分区数按集群规模调整（建议 ≈ executor 核数 × 2~3）
spark.sql.shuffle.partitions=12

# ④ 结果库：填写集群各节点都能访问到的 MySQL 地址
jdbc.url=jdbc:mysql://192.168.10.1:3306/sales_analysis?useUnicode=true&characterEncoding=utf8&useSSL=false
jdbc.user=root
jdbc.password=你的口令

# ⑤ Linux 环境不需要 winutils，留空
hadoop.home.dir=
```

### 关于 MySQL 位置的三个方案

| 方案 | 说明 | 需注意 |
| --- | --- | --- |
| **MySQL 装在 Windows 宿主机（本项目实测方案，推荐）** | 沿用现有环境，`jdbc.url` 填 **`192.168.10.1:3306`** | 已实测可用，宿主机无需任何改动（详见 0.3 节） |
| MySQL 装在虚拟机内 | 需在虚拟机里另装一套 MySQL | 要重跑一次建库；虚拟机磁盘会再占几 GB |
| MySQL 装在独立服务器 | 生产推荐 | 注意网络互通与账号授权（`GRANT ... TO 'root'@'%'`） |

> **重要**：Spark 的 executor 分布在多个节点，**每个节点都必须能访问 MySQL**，
> 因为结果数据是由 executor 直接通过 JDBC 写入的。若无法互通，可改为"先写 HDFS、再单独导入 MySQL"。

若使用 Windows 宿主机上的 MySQL，需要先授权远程访问：

```sql
-- 在宿主机 MySQL 中执行
GRANT ALL PRIVILEGES ON sales_analysis.* TO 'root'@'%' IDENTIFIED BY '123456';
FLUSH PRIVILEGES;
```

并确认 Windows 防火墙允许 3306 入站。

---

## 6. 提交作业

```bash
cd spark-sales-analysis

# 默认使用 local[*]（推荐，两种计算方式都会跑）
scripts/linux/run-etl-cluster.sh

# 只跑 DataFrame 方式
scripts/linux/run-etl-cluster.sh --mode=df

# 跳过 ODS 装载（数据已入库时用，可省约 15 秒）
scripts/linux/run-etl-cluster.sh --no-ods

# 提交到 YARN（client 模式；注意 Hadoop 3.1.3 的版本兼容风险，见 0.4 节）
MASTER=yarn scripts/linux/run-etl-cluster.sh

# 使用 Spark 独立集群（Standalone）模式
MASTER=spark://hadoop:7077 scripts/linux/run-etl-cluster.sh

# 使用 YARN cluster 模式（Driver 也在集群里跑）
MASTER=yarn DEPLOY_MODE=cluster scripts/linux/run-etl-cluster.sh
```

脚本内部等价于：

```bash
spark-submit \
  --class com.sales.SalesAnalysisApplication \
  --master yarn \
  --deploy-mode client \
  --name spark-sales-analysis \
  --files spark-job.properties \
  --conf spark.driver.extraJavaOptions="-Dconfig.file=spark-job.properties" \
  --conf spark.executor.extraJavaOptions="-Dconfig.file=spark-job.properties" \
  --conf spark.sql.shuffle.partitions=12 \
  --conf spark.executor.memory=2g \
  --conf spark.executor.cores=2 \
  spark-job-1.0.0-cluster.jar --mode=both
```

> **`--files` 的作用**：把 `spark-job.properties` 分发到 Driver 与所有 Executor 的工作目录，
> 再通过 `-Dconfig.file` 指定加载，实现"配置随任务分发"。
> `JobConfig` 的加载优先级为：classpath → 外部文件 → JVM 系统属性。

---

## 7. 结果验证

```bash
# ① 查看 YARN 应用状态
yarn application -list
# 或访问 http://<resourcemanager>:8088

# ② 查看作业日志（作业结束后可用）
yarn logs -applicationId application_xxxxxxxxxxxx_0001 | grep -E "数据清洗统计|TableWriter|耗时"

# ③ 查看 Spark 任务详情（Standalone 模式）
#    http://<master>:8080
```

```bash
# ④ 在结果库中确认指标
mysql -h <mysql-host> -uroot -p sales_analysis -e "
  SELECT kpi_name, kpi_value, kpi_unit FROM ads_overview ORDER BY id;
  SELECT compute_mode, duration_ms, input_rows, output_rows FROM etl_job_log ORDER BY id;
  SELECT raw_cnt, valid_cnt, discard_cnt, quality_score FROM ads_clean_stat;"
```

### 集群模式典型输出

```
[Main] Spark 版本：3.5.9，运行模式：由 spark-submit 指定
[Main] 数据源：hdfs://192.168.10.128:8020/sales/raw/ods_order_detail.csv
[OdsLoader] ods_order_detail 装载 200177 行
---------------- 数据清洗统计 ----------------
原始记录数        : 200177
R01 含空值记录    : 6730
...
有效记录数        : 193061
数据质量得分      : 0.9645
[TableWriter] ads_overview 写入 9 行
...
======================================================
  两种计算方式运行效果对比（etl_job_log）
======================================================
计算方式         总耗时(ms)        输入记录数          状态
DATAFRAME    ...
RDD          ...
```

---

## 8. 一键数据链路（答辩演示建议）

在答辩演示时，推荐按以下顺序展示完整链路：

```bash
# 步骤 1：确认 HDFS 有数据
hdfs dfs -ls -h /sales/raw

# 步骤 2：提交离线作业（默认 local[*]，在 Linux 环境里跑 Spark）
scripts/linux/run-etl-cluster.sh --mode=both

# 步骤 3：确认结果已入库
mysql -h <mysql-host> -uroot -p sales_analysis -e "SELECT COUNT(*) FROM dwd_order_detail;"

# 步骤 4：启动后端服务（在能访问 MySQL 的机器上）
java -jar server/target/sales-analysis-server.jar

# 步骤 5：浏览器打开看板做演示
#   http://localhost:8080/
#   http://localhost:8080/swagger-ui.html
```

---

## 9. 常见问题

**Q1：`java.lang.ClassNotFoundException: com.mysql.jdbc.Driver`**
`cluster-package` 已把 MySQL 驱动打进 jar。若使用默认的瘦包，需要额外加 `--jars`：
```bash
spark-submit --jars /path/mysql-connector-java-5.1.49.jar ...
```

**Q2：`org.apache.hadoop.security.AccessControlException: Permission denied: user=...`**
HDFS 目录权限问题。在 HDFS 上放宽权限或切换到有权限的用户：
```bash
hdfs dfs -chmod -R 777 /sales/raw
# 或者用提交用户创建目录
```

**Q3：`Failed on local exception: java.net.ConnectException: Connection refused`**
`HADOOP_CONF_DIR` 未正确设置，Spark 找不到 NameNode 地址。检查 `spark-env.sh` 中是否导出了 `HADOOP_CONF_DIR`。

**Q4：作业卡在 `Job ... is running` 很久，或报 Container 被 killed**
通常是 executor 内存不足。调整 `--conf spark.executor.memory`（或 YARN 的 `yarn.nodemanager.resource.memory-mb`）。
本作业在单机 4 分区下约 30~55 秒完成，集群模式通常更快。

**Q5：写入 MySQL 报 `Public Key Retrieval is not allowed`**
这是 MySQL 8 的问题。本项目适配 MySQL 5.5 + 驱动 5.1.49，不会有此问题；
若改用 MySQL 8，需在 URL 加 `allowPublicKeyRetrieval=true` 并升级驱动。

**Q6：`tmp_table_size` 相关查询慢**
MySQL 5.5 默认 `tmp_table_size` 只有 18MB，`COUNT(DISTINCT)` 的临时表会溢写磁盘，查询慢十几倍。
后端已通过 JDBC 的 `sessionVariables` 参数在连接级调大；若在 MySQL 全局层面优化，可修改 `my.ini`：

```ini
tmp_table_size=134217728
max_heap_table_size=134217728
innodb_buffer_pool_size=512M     # 按物理内存调整
```

**Q7：想同时保留单机模式的演示能力**
不需要改代码。集群上跑完作业后，把 `data.raw.dir` 改回 `data/raw`、`spark.master` 改回 `local[*]`、
`hadoop.home.dir` 留空即可在 Linux 上以单机模式复跑；Windows 上按 README 配置 winutils 即可。
