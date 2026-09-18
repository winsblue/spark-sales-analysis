# 九、Linux 大数据集群部署与提交指南

> 本文档说明如何把本项目部署到 **已有 Hadoop 环境** 的 Linux 虚拟机上运行。
> 项目同时支持两种运行环境，**只需改配置、无需改代码**：

| 运行环境 | 适用场景 | Spark 运行模式 | 数据源 | 结果库 |
| --- | --- | --- | --- | --- |
| Windows / Linux 单机 | 本地开发、逻辑验证、答辩演示 | `local[*]` | 本地文件 | 本机 MySQL |
| Linux 大数据集群 | 生产形态、体现集群处理能力 | YARN 或 Standalone | HDFS | 集群可访问的 MySQL |

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
sudo ./scripts/linux/setup-spark.sh

# 让环境变量生效
source /etc/profile
spark-submit --version
```

脚本会自动完成：

1. 检查 `java` / `hadoop` 环境，自动推断 `JAVA_HOME` 与 `HADOOP_CONF_DIR`；
2. 依次尝试清华、阿里云、Apache 归档三个下载源获取 `spark-3.5.1-bin-hadoop3.tgz`；
3. 解压到 `/opt/module/spark`（已存在则先备份）；
4. 生成 `conf/spark-env.sh`，写入 `JAVA_HOME` / `HADOOP_CONF_DIR`；
5. 写入 `/etc/profile.d/spark.sh` 配置 `PATH`；
6. 用 `spark-submit --version` 自检。

### 版本选择建议

| 集群 Hadoop 版本 | 建议 Spark 版本 | 说明 |
| --- | --- | --- |
| Hadoop 3.x | Spark 3.5.x（bin-hadoop3） | 本项目验证版本，推荐 |
| Hadoop 2.7 ~ 2.10 | Spark 3.0~3.3（bin-hadoop2.7） | 用 `SPARK_VERSION=3.3.4` 覆盖脚本变量 |
| Hive 3.x 已部署 | Spark 3.5.x | 如需读写 Hive 表，把 `hive-site.xml` 放到 `$SPARK_HOME/conf` |

> 覆盖版本示例：
> ```bash
> SPARK_VERSION=3.3.4 ./scripts/linux/setup-spark.sh
> ```
> 注意：脚本按 `spark-<版本>-bin-hadoop3` 命名下载，Hadoop 2.x 环境请手动下载 `bin-hadoop2.7` 包。

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

`cluster-package` 这个 profile 只打包**本项目代码 + MySQL 驱动**，
Spark / Scala / Hadoop 由集群提供，jar 体积小（约 200KB）。

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
data.raw.dir=hdfs://node1:8020/sales/raw

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
| MySQL 装在 NameNode 节点 | 最简单，`jdbc.url` 填本机或内网 IP | 需保证所有 NodeManager 节点都能连上该端口 |
| MySQL 装在 Windows 宿主机 | 沿用现有环境 | 虚拟机需能访问宿主机 IP（NAT 模式下用宿主机内网 IP），并放开 Windows 防火墙 3306 端口 |
| MySQL 装在独立服务器 | 生产推荐 | 同上，注意网络互通与账号授权（`GRANT ... TO 'root'@'%'`） |

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

# 默认提交到 YARN（client 模式），两种计算方式都会跑
scripts/linux/run-etl-cluster.sh

# 只跑 DataFrame 方式
scripts/linux/run-etl-cluster.sh --mode=df

# 跳过 ODS 装载
scripts/linux/run-etl-cluster.sh --no-ods

# 使用 Spark 独立集群（Standalone）模式
MASTER=spark://node1:7077 scripts/linux/run-etl-cluster.sh

# 使用 YARN cluster 模式（Driver 也在集群里跑）
DEPLOY_MODE=cluster scripts/linux/run-etl-cluster.sh
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
[Main] Spark 版本：3.5.1，运行模式：由 spark-submit 指定
[Main] 数据源：hdfs://node1:8020/sales/raw/ods_order_detail.csv
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

# 步骤 2：提交离线作业，观察 YARN 上起任务
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
