# 交付说明（拿到手先看这里）

> 这是一个「离线数仓 + 服务化」的实习实践项目：**Spark 离线计算 + MySQL 结果库 + Spring Boot 接口 + Vue/ECharts 看板**。
> 本文件告诉你**怎么最快看到效果**，以及想深入时去哪儿看。

---

## 一、最快路径：5 分钟看到看板（免构建）

**你只需要装两样东西：JDK 8+ 和 MySQL。** 不需要 Maven、不需要 Node、不需要 Spark。

### 第 1 步：恢复数据库快照

`prebuilt/sales_analysis-dump.sql.gz` 是已经跑好数据的结果库快照（约 17 MB，解压后约 104 MB）。

**Linux / macOS：**

```bash
gunzip < prebuilt/sales_analysis-dump.sql.gz | mysql -uroot -p
```

**Windows：**

1. 用 7-Zip / WinRAR 把 `sales_analysis-dump.sql.gz` 解压成 `sales_analysis-dump.sql`；
2. 然后执行（把 `C:\MySQLServer5.5\bin` 换成你自己的 MySQL 安装目录）：

```cmd
C:\MySQLServer5.5\bin\mysql.exe -uroot -p < prebuilt\sales_analysis-dump.sql
```

> 快照里已包含 `CREATE DATABASE` 语句，**不用先建库**。
> 导入完可以验证一下：`mysql -uroot -p -e "SELECT COUNT(*) FROM sales_analysis.dwd_order_detail;"` → 应返回 **193061**。

### 第 2 步：启动后端

```bash
java -jar prebuilt/sales-analysis-server.jar
```

如果数据库口令不是 `123456`，启动时用参数覆盖（不用改文件）：

```bash
java -jar prebuilt/sales-analysis-server.jar --spring.datasource.password=你的口令
```

### 第 3 步：打开浏览器

| 页面 | 地址 |
| --- | --- |
| **销售分析看板** | <http://localhost:8080/> |
| 接口文档（可在线调试） | <http://localhost:8080/swagger-ui.html> |

> 前端页面已经打包进 jar 里了，**不需要另外启动 Node**。

---

## 二、想完整跑一遍全流程（约 30 分钟）

需要额外装 **Maven**；要跑离线作业还需要 **Spark**（Windows 上还要 `winutils.exe`）。

```bash
# ① 改配置：数据库地址与口令（两个文件都要改）
#    spark-job/src/main/resources/spark-job.properties
#    server/src/main/resources/application.yml

# ② 构建（首次会下载依赖，建议先配阿里云镜像，见 docs/11 第 4 节）
mvn -B clean package -DskipTests

# ③ 跑离线作业（会自动建库建表、生成模拟数据、清洗、统计、落库）
#    Windows:
scripts\run-etl.cmd --mode=both
#    Linux:
scripts/linux/run-etl-cluster.sh --mode=both

# ④ 启动后端
scripts\start-server.cmd

# ⑤（可选）重建前端
cd web && npm install && npm run build
```

**完整步骤见 `spark-sales-analysis/docs/11-项目交付与迁移指南.md`**，里面有环境清单、
数据库三条给法、以及 5 个最容易踩的坑（尤其是 MySQL 8 的认证插件问题）。

---

## 三、建议的阅读顺序

| 你想干什么 | 看哪份文档 |
| --- | --- |
| 先了解这是个什么项目 | `spark-sales-analysis/README.md` |
| 想知道每一步怎么部署 | `docs/11-项目交付与迁移指南.md` |
| **想搞懂原理和每个设计决策** | **`docs/10-项目教学手册.md`**（11 章，含 30 道自测题） |
| 看需求/建模/流程/测试等交付文档 | `docs/01` ~ `docs/09` |
| 部署到 Linux 集群 | `docs/09-Linux集群部署指南.md` |
| 看提交历史（48 条） | `git log --oneline`（`.git` 已包含在包里） |

---

## 四、包内容清单

```
├── 交付说明.md                      ← 本文件
├── spark-sales-analysis/            ← 完整源码（含 .git，48 条提交）
│   ├── spark-job/                   离线计算模块（Spark）
│   ├── server/                      后端服务（Spring Boot）
│   ├── web/                         前端（Vue3 + ECharts，源码）
│   ├── docs/                        11 篇文档
│   ├── sql/01_schema.sql            建库建表脚本
│   └── scripts/                     一键脚本（Windows .cmd / Linux .sh）
└── prebuilt/                        ← 免构建运行用
    ├── sales-analysis-server.jar    可执行后端（含前端页面，29 MB）
    ├── spark-job-1.0.0-cluster.jar  集群提交包（1 MB，给 spark-submit 用）
    └── sales_analysis-dump.sql.gz   数据库快照（含 19.3 万行明细与全部结果表）
```

> 包里**故意没有**放：`web/node_modules`（95 MB，`npm install` 可生成）、
> `data/raw/*.csv`（25 MB，跑一次 `--generate` 可生成，固定随机种子结果一致）、
> 各模块的 `target/` 中间产物。

---

## 五、几个关键数字（方便你判断是否正常）

| 指标 | 值 |
| --- | --- |
| 原始订单明细 | 200,177 行 |
| 清洗后有效明细 | 193,061 行（质量得分 96.45%） |
| GMV | 420,338,798.88 元 |
| 接口数量 | 15 个 |
| 作业耗时（DataFrame / RDD） | 约 33 秒 / 约 50 秒 |

如果导入快照后查到的 `dwd_order_detail` 行数不是 193,061，说明导入有问题，请回看第一节。
