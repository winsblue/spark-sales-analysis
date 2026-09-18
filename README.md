# 基于 Spark 的电商商品销售离线分析与 Java 服务化应用

> 生产实习（教学内容 · 大数据方向）实践项目 3
> 项目分层达成：**基础层（合格）+ 提高层（良好）**

一套完整的「**数据生成 → 数据入库 → Spark 离线清洗与统计 → 结果落库 → Java 服务化 → Web 可视化**」链路实现。
后端以 **Java / Spring Boot** 技术体系为主，大数据处理采用 **Apache Spark**（单机模式，无需 Hadoop 集群）。

---

## 一、项目背景与目标

电商平台的销售数据分散在多个业务表中，运营人员需要每天了解「卖了多少、谁在买、什么好卖、哪个渠道更高效」。
本项目模拟真实生产场景：由数据工程师产出离线指标，由后端工程师把指标服务化，由前端工程师完成可视化看板。

**业务目标**

| 分析主题 | 需要回答的问题 |
| --- | --- |
| 整体经营 | 本期 GMV、订单量、用户数、客单价、退款率是多少？ |
| 时间趋势 | 销售是上升还是下降？周末是否明显高于工作日？ |
| 商品结构 | 哪些类目、哪些商品贡献了主要销售额？ |
| 渠道效率 | APP、小程序、天猫、京东各渠道的销售占比与效率如何？ |
| 区域分布 | 哪些省份是核心市场？ |
| 支付习惯 | 用户更偏好哪种支付方式？ |
| 渠道 × 类目 | 同一类目在不同渠道的表现差异有多大？ |

---

## 二、技术栈

| 层次 | 技术选型 | 说明 |
| --- | --- | --- |
| 数据计算 | **Apache Spark 3.5.x**（开发验证 3.5.1 / 集群可用 3.5.9） | RDD 与 DataFrame/SparkSQL 双实现，支持 `local[*]` 与 YARN 提交 |
| 结果存储 | **MySQL 5.5.27**（InnoDB / utf8） | ODS / DWD / ADS 三层结构 |
| 后端服务 | **Spring Boot 2.7.18 + MyBatis-Plus 3.5.3 + OpenAPI 3** | JDK 8 环境（Spring Boot 2.7 是支持 JDK 8 的最后一个分支） |
| 前端可视化 | **Vue 3 + Vite 5 + ECharts 5 + Axios** | 折线、柱状、饼图、分组对比与明细下钻 |
| 构建工具 | Maven 3.8.8（多模块） | spark-job / server 两个模块 |
| 运行环境 | Windows 10 + JDK 1.8.0_212 | Windows 下 Spark 需要 winutils.exe |

---

## 三、系统架构

```
┌──────────────────────────────────────────────────────────────────┐
│                      数据源（模拟电商平台）                        │
│        data/raw/ods_order_detail.csv   data/raw/ods_product.csv   │
└───────────────────────────────┬──────────────────────────────────┘
                                │ ① 读取（Spark textFile / csv）
┌───────────────────────────────▼──────────────────────────────────┐
│                    spark-job（Spark 离线计算模块）                 │
│  ② 写入 ODS 原始层                                                │
│  ③ 数据清洗：空值填充 / 去重 / 时间规整 / 数量与单价校验 /          │
│     金额重算 / 状态标准化 / 维度关联缺失处理                        │
│  ④ 写入 DWD 明细层 + 异常留痕表                                    │
│  ⑤ 多维指标统计（DataFrame 与 RDD 两套实现，做性能对比）            │
│  ⑥ 写入 ADS 指标结果层 + 数据质量统计 + 作业运行日志                │
└───────────────────────────────┬──────────────────────────────────┘
                                │ ⑦ JDBC
┌───────────────────────────────▼──────────────────────────────────┐
│                        MySQL · sales_analysis                     │
│   ODS：ods_order_detail / ods_product                             │
│   DWD：dwd_order_detail / dwd_clean_reject                        │
│   ADS：ads_overview / ads_daily_trend / ads_category_stat /       │
│        ads_product_stat / ads_channel_stat / ads_paytype_stat /   │
│        ads_region_stat / ads_channel_category_stat                │
│   监控：ads_clean_stat / etl_job_log                               │
└───────────────────────────────┬──────────────────────────────────┘
                                │ ⑧ MyBatis-Plus 查询
┌───────────────────────────────▼──────────────────────────────────┐
│                  server（Spring Boot 后端服务）                    │
│   /api/overview、/api/trend、/api/category/topn、/api/product/topn │
│   /api/channel/dist、/api/paytype/dist、/api/region/topn          │
│   /api/channel-category、/api/order/page、/api/order/export       │
│   /api/monitor/quality、/api/monitor/job-log、/api/monitor/        │
│   consistency                                                     │
│   查询策略：无维度筛选走 ADS 预计算；带维度筛选走 DWD 即席聚合       │
└───────────────────────────────┬──────────────────────────────────┘
                                │ ⑨ HTTP / JSON
┌───────────────────────────────▼──────────────────────────────────┐
│                     web（Vue3 + ECharts 可视化看板）               │
│   指标卡 / 趋势图 / 类目排行 / 渠道占比 / 区域排行 / 交叉对比 /      │
│   商品排行 / 明细下钻 / CSV 导出 / 运行监控                        │
└──────────────────────────────────────────────────────────────────┘
```

---

## 四、目录结构

```
spark-sales-analysis/
├── pom.xml                        Maven 聚合工程
├── README.md
├── docs/                          实习交付文档
│   ├── 01-需求分析.md
│   ├── 02-数据来源与数据字典.md
│   ├── 03-概念模型与逻辑模型设计.md
│   ├── 04-数据处理流程.md
│   ├── 05-核心模块实现思路与关键代码.md
│   ├── 06-运行调试与测试分析.md
│   ├── 07-实习总结.md
│   ├── 08-答辩PPT大纲.md
│   ├── 09-Linux集群部署指南.md
│   ├── 10-项目教学手册.md          （自学 / 答辩准备用，含原理、决策依据、面试问答）
│   ├── 11-项目交付与迁移指南.md     （复制到别的电脑 / 交给老师怎么操作）
│   └── 12-答辩演示操作手册.md       （答辩当天照着做：启动、演示脚本、话术、应急预案）
├── sql/
│   └── 01_schema.sql              建库建表脚本（ODS / DWD / ADS + 监控表）
├── data/
│   ├── raw/                       原始数据（运行后生成，不提交）
│   └── sample/                    样例数据（随代码提交，便于查看数据形态）
├── spark-job/                     Spark 离线计算模块
│   └── src/main/java/com/sales/
│       ├── SalesAnalysisApplication.java    作业统一入口
│       ├── config/JobConfig.java            配置加载
│       ├── common/Schemas.java              常量与表结构定义
│       ├── generator/MockDataGenerator.java 模拟数据生成器
│       ├── clean/DataFrameCleaner.java      DataFrame 方式清洗
│       ├── analysis/DataFrameAnalysis.java  DataFrame 方式多维统计
│       ├── analysis/RddAnalysis.java        RDD 方式清洗与统计
│       └── sink/                            结果落库（JDBC）
├── server/                        Spring Boot 后端
│   └── src/main/
│       ├── java/com/sales/server/
│       │   ├── controller/       接口层
│       │   ├── service/          业务层
│       │   ├── mapper/           持久层
│       │   ├── entity/           实体
│       │   ├── vo/               视图对象
│       │   ├── dto/              查询条件
│       │   ├── config/           分页、跨域、接口文档配置
│       │   └── common/           统一响应与全局异常
│       └── resources/
│           ├── application.yml
│           └── mapper/AnalysisMapper.xml    核心分析 SQL
├── web/                           Vue3 前端
│   └── src/{api,components,views,utils,styles,router}
└── scripts/                       一键运行脚本
    ├── run-etl.cmd                执行离线计算作业（Windows 单机）
    ├── start-server.cmd           启动后端服务
    ├── start-web-dev.cmd          启动前端开发服务器
    ├── build-all.cmd              全量构建（含前端打包）
    ├── package-dist.ps1           生成交付包（给别人用的 zip，见 docs/11）
    ├── demo-start.ps1             答辩演示用：一键启动两个实例（真实数据 8080 + 模拟数据 8081）
    ├── demo-stop.ps1              停止演示实例
    ├── templates/                 交付说明模板（打包时复制进包内）
    ├── verify/                    可重复验证脚本（对账 / 最小回归样例）
    └── linux/                     Linux 集群相关脚本
        ├── setup-spark.sh         安装并配置 Spark
        ├── upload-to-hdfs.sh      上传数据到 HDFS
        └── run-etl-cluster.sh     提交作业到 YARN / Spark 集群
```

---

## 五、环境要求

| 组件 | 版本要求 | 本项目验证版本 |
| --- | --- | --- |
| JDK | 1.8（必须，Spring Boot 2.7 与 Spark 3.5 均需 JDK 8/11） | 1.8.0_212 |
| Maven | 3.6+ | 3.8.8 |
| MySQL | 5.5+ | 5.5.27 |
| Node.js | 18+ | 22.22.2 |
| 操作系统 | Windows 10 / Linux / macOS | Windows 10 |

### Windows 下运行 Spark 的额外准备

Spark 在 Windows 上需要 Hadoop 的 `winutils.exe` 与 `hadoop.dll`，否则会报
`Could not locate executable null\bin\winutils.exe`。本项目使用 `HADOOP_HOME` 方式配置：

1. 下载 `hadoop-3.3.6/bin/winutils.exe` 与 `hadoop.dll`（开源仓库 `cdarlint/winutils`）；
2. 放到任意目录，例如 `C:\hadoop\bin\`；
3. 配置 `spark-job/src/main/resources/spark-job.properties` 中的 `hadoop.home.dir=C:/hadoop`
   （或在系统环境变量中设置 `HADOOP_HOME`）。

> Linux / macOS 无需该步骤，把 `hadoop.home.dir` 留空即可。

---

## 六、快速开始

### 步骤 1 · 初始化数据库

确认 MySQL 已启动，并准备好账号口令。数据库与表由作业自动创建，无需手工执行脚本。
如需手工执行，可直接运行 `sql/01_schema.sql`。

### 步骤 2 · 配置连接信息

- 离线作业：`spark-job/src/main/resources/spark-job.properties`
- 后端服务：`server/src/main/resources/application.yml`

默认使用 `127.0.0.1:3306`、`root / 123456`、库名 `sales_analysis`，请按实际环境修改。

### 步骤 3 · 准备数据（两条路，任选其一）

**路线 A（推荐，真实数据）**：Olist 巴西电商公开数据集，10 万笔真实订单

```bash
scripts\download-real-data.ps1   # 下载原始数据（约 48 MB，走 CDN 无需代理）
scripts\run-etl.cmd --import-real  # 转换为本项目 ODS 标准格式
```

产物：`data/real/ods_order_detail.csv`（**102,425 行**）+ `data/real/ods_product.csv`（32,951 条）

> 转换后的数据与模拟数据**格式完全同构**，因此后续清洗 / 统计 / 落库代码零改动。
> 字段映射关系见 `docs/02-数据来源与数据字典.md` 第 1.2 节。

**路线 B（对照，模拟数据）**：20 万行，主动注入 8 类脏数据，用于验证清洗规则

```bash
scripts\run-etl.cmd --generate
```

产物：`data/raw/ods_order_detail.csv`（约 20 万行，含脏数据）+ `data/raw/ods_product.csv`（199 条）

> 两条路线通过 `spark-job.properties` 的 `data.raw.dir` 切换（`data/real` 或 `data/raw`），
> **不需要改任何代码**。注意两条路线的**货币单位不同**（真实数据是 BRL，模拟数据是元），
> 需同步修改 `data.currency.unit` 与后端 `application.yml` 的 `app.currency.unit`。

### 步骤 4 · 执行离线计算作业

```bash
scripts\run-etl.cmd --mode=both
```

`--mode` 可选值：`df`（仅 DataFrame）、`rdd`（仅 RDD）、`both`（默认，两种都跑并输出性能对比）。

作业会自动完成：建表 → ODS 装载 → 清洗 → DWD 落库 → 多维统计 → ADS 落库 → 质量统计 → 作业日志。

### 步骤 5 · 构建并启动后端

```bash
scripts\build-all.cmd          # 编译 spark-job + server，并打包前端
scripts\start-server.cmd       # 启动后端，默认 8080 端口
```

- 可视化看板：<http://localhost:8080/>
- 接口文档：<http://localhost:8080/swagger-ui.html>

### 步骤 6 · 前端独立开发模式（可选）

```bash
cd web
npm install
npm run dev      # http://127.0.0.1:5173，已配置 /api 代理到 8080
```

---

## 六之二、Linux 大数据集群模式（Hadoop + Spark）

项目同时支持 **Windows/Linux 单机模式** 与 **Linux 大数据集群模式**，只需改配置、无需改代码：

| 运行环境 | Spark 运行模式 | 数据源 | 提交方式 | 常驻进程 |
| --- | --- | --- | --- | --- |
| Windows / Linux 单机 | `local[*]` | 本地文件 | `scripts\run-etl.cmd` | 无 |
| Linux 大数据集群 | YARN / Standalone | HDFS | `scripts/linux/run-etl-cluster.sh` | NameNode + NodeManager 等（集群自身） |

### 集群模式三步走

```bash
# 1) 安装并配置 Spark（集群已有 Hadoop 时执行）
./scripts/linux/setup-spark.sh && source /etc/profile

# 2) 生成数据并上传到 HDFS
scripts/linux/upload-to-hdfs.sh

# 3) 构建集群提交包并提交到 YARN
mvn -pl spark-job -am -Pcluster-package clean package -DskipTests
scripts/linux/run-etl-cluster.sh
```

切换模式只需要调整 `spark-job.properties` 中的三项：

```properties
# 集群模式
data.raw.dir=hdfs://node1:8020/sales/raw   # 数据源改为 HDFS
spark.master=                              # 留空，由 spark-submit --master 决定
jdbc.url=jdbc:mysql://<集群可达的IP>:3306/sales_analysis?...   # 所有节点都能访问的 MySQL
hadoop.home.dir=                           # Linux 无需 winutils
```

> **完整步骤、版本对应关系、网络与 MySQL 部署注意事项、常见报错处理**，
> 见 **`docs/09-Linux集群部署指南.md`**。

---


## 七、接口清单

| 分类 | 接口 | 说明 |
| --- | --- | --- |
| 看板 | `GET /api/overview` | 9 项核心指标（GMV、下单量、客单价等） |
| 看板 | `GET /api/trend` | 按天销售趋势 |
| 看板 | `GET /api/category/topn` | 类目排行与占比 |
| 看板 | `GET /api/product/topn` | 商品排行 TopN |
| 看板 | `GET /api/channel/dist` | 渠道分布 |
| 看板 | `GET /api/paytype/dist` | 支付方式分布 |
| 看板 | `GET /api/region/topn` | 省份排行 |
| 看板 | `GET /api/channel-category` | 渠道 × 类目交叉对比 |
| 看板 | `GET /api/meta/filters` | 筛选条件候选项 |
| 明细 | `GET /api/order/page` | 明细分页查询（多条件） |
| 明细 | `GET /api/order/export` | 明细 CSV 导出 |
| 监控 | `GET /api/monitor/quality` | 数据质量报告 |
| 监控 | `GET /api/monitor/job-log` | 作业运行记录（性能对比） |
| 监控 | `GET /api/monitor/consistency` | 数据一致性校验 |

公共查询参数：`startDate`、`endDate`、`channel`、`categoryName`、`province`、`payType`、`limit`、`pageNum`、`pageSize`。

统一响应结构：

```json
{ "code": 0, "message": "success", "data": {}, "timestamp": 1789000000000 }
```

---

## 八、分层达成情况

### 基础层（合格）

| 要求 | 实现情况 |
| --- | --- |
| 完成数据读取、清洗、转换、聚合和结果输出 | `spark-job` 模块完成 CSV 读取 → 8 条清洗规则 → 类型规整 → 分组聚合 → 落库 |
| 形成不少于 5 项统计指标 | 实现 9 项核心指标 + 7 张多维结果表 |
| 通过 Java Web 页面展示 | Spring Boot 提供 RESTful 接口，Vue3 + ECharts 完成看板 |

### 提高层（良好）

| 要求 | 实现情况 |
| --- | --- |
| 实现多维分析、时间趋势、TopN、分组对比 | 类目 / 商品 / 渠道 / 支付方式 / 省份 5 个维度 + 渠道×类目交叉对比 + 按天趋势 |
| 结果持久化 | ODS / DWD / ADS 三层共 14 张表落库 |
| 比较不同处理方式的运行效果 | RDD 与 DataFrame 双实现，记录耗时并输出对比结论 |
| 完善分页、查询和导出功能 | 明细物理分页、多条件筛选、CSV 导出（含 BOM，Excel 不乱码） |

> **未实现拓展层内容**：实时接入（Kafka/Flink）、权限控制、定时调度、缓存优化、数据倾斜调优等均属于拓展层要求，本项目按要求不做实现。

---

## 九、常见问题

**Q1：运行作业报 `Could not locate executable null\bin\winutils.exe`**
Windows 环境下未配置 hadoop.home.dir，参见「五、环境要求 → Windows 下运行 Spark 的额外准备」。

**Q2：后端启动报 `Unable to connect to database`**
确认 MySQL 已启动、库已通过步骤 4 创建、`application.yml` 中的账号口令正确。

**Q3：页面显示"未查询到数据"**
说明离线作业尚未执行或结果表为空，请先执行 `scripts\run-etl.cmd --mode=both`。

**Q4：MySQL 5.5 是否支持 utf8mb4？**
不支持。本项目建表统一使用 `utf8` 字符集与 `mysql-connector-java 5.1.49` 驱动，
若使用 MySQL 8 请把字符集改为 `utf8mb4` 并把驱动升级为 `mysql-connector-java 8.0.x`。

**Q5：换一台机器运行需要改什么？**
只需三处：`spark-job.properties` 的 `jdbc.*` 与 `hadoop.home.dir`、`application.yml` 的 `datasource.*`。
