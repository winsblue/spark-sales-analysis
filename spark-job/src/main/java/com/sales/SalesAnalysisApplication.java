package com.sales;

import com.sales.analysis.DataFrameAnalysis;
import com.sales.analysis.RddAnalysis;
import com.sales.clean.DataFrameCleaner;
import com.sales.common.Schemas;
import com.sales.config.JobConfig;
import com.sales.generator.MockDataGenerator;
import com.sales.generator.RealDataImporter;
import com.sales.sink.MysqlSink;
import com.sales.sink.OdsLoader;
import com.sales.sink.SchemaInitializer;
import com.sales.sink.TableWriter;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;

import java.io.File;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 离线分析作业统一入口。
 *
 * <p>执行流程：</p>
 * <ol>
 *   <li>确保数据库与表结构就绪（读取 sql/01_schema.sql）</li>
 *   <li>生成模拟数据（若源文件不存在则自动生成）</li>
 *   <li>按指定的计算方式（DataFrame / RDD / 两者）执行：
 *     <br>读源数据 -&gt; 写入 ODS -&gt; 数据清洗 -&gt; 写入 DWD -&gt; 多维指标统计 -&gt; 写入 ADS</li>
 *   <li>记录作业运行信息，输出两种计算方式的性能对比</li>
 * </ol>
 *
 * <p>命令行参数：</p>
 * <pre>
 *   --mode=df|rdd|both   计算方式，默认 both（两种都跑，用于性能对比）
 *   --generate           仅生成模拟数据后退出
 *   --import-real        把 Olist 真实数据集转换为本项目 ODS 标准格式后退出
 *   --no-ods             跳过 ODS 原始层装载
 *   --no-init-db         跳过建库建表
 *   --sql=path           指定建表脚本路径，默认 sql/01_schema.sql
 * </pre>
 */
public class SalesAnalysisApplication {

    private static final String MODE_DATAFRAME = "DATAFRAME";
    private static final String MODE_RDD = "RDD";
    private static final String JOB_NAME = "sales-offline-analysis";

    private static final String[] CLEAN_STAT_COLUMNS = {
            "batch_id", "run_date", "raw_cnt", "null_field_cnt", "dup_record_cnt",
            "invalid_time_cnt", "invalid_amount_cnt", "invalid_status_cnt", "missing_dim_cnt",
            "valid_cnt", "discard_cnt", "quality_score", "duration_ms", "update_time"
    };

    private static final String[] JOB_LOG_COLUMNS = {
            "batch_id", "job_name", "compute_mode", "start_time", "end_time",
            "duration_ms", "input_rows", "output_rows", "job_status", "remark"
    };

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parseArgs(args);
        String mode = opts.getOrDefault("mode", "both").toLowerCase();

        System.out.println("======================================================");
        System.out.println("  基于 Spark 的电商商品销售离线分析与 Java 服务化应用");
        System.out.println("  离线计算作业启动");
        System.out.println("======================================================");

        JobConfig config = JobConfig.load();
        config.prepareHadoopHome();

        // ---------- 0. 真实数据集导入（纯文件转换，不依赖数据库，因此放在最前） ----------
        if (opts.containsKey("import-real")) {
            RealDataImporter.importToOds(config);
            System.out.println("[Main] 真实数据已转换为 ODS 标准格式，作业退出");
            return;
        }

        // ---------- 1. 数据库准备 ----------
        MysqlSink.ensureDatabase(config.jdbcUrl(), config.jdbcUser(), config.jdbcPassword());
        MysqlSink sink = new MysqlSink(config.jdbcUrl(), config.jdbcUser(), config.jdbcPassword());
        sink.ping();
        System.out.println("[Main] 结果库连接正常：" + config.jdbcUrl());

        if (!opts.containsKey("no-init-db")) {
            SchemaInitializer.init(sink, opts.getOrDefault("sql", "sql/01_schema.sql"));
        }

        // ---------- 2. 模拟数据准备（--generate 仅生成数据，不启动 Spark） ----------
        if (opts.containsKey("generate")) {
            MockDataGenerator.generate(config);
            System.out.println("[Main] 已完成模拟数据生成，作业退出");
            return;
        }

        // ---------- 3. 创建 Spark 会话 ----------
        SparkSession spark = buildSparkSession(config);
        System.out.println("[Main] Spark 版本：" + spark.version() + "，运行模式："
                + (config.masterManagedBySubmit() ? "由 spark-submit 指定" : config.sparkMaster()));

        try {
            // 数据源可能在本地，也可能在 HDFS：统一用 Hadoop FileSystem API 判断
            if (!pathExists(config.orderRawFile()) || !pathExists(config.productRawFile())) {
                if (isRemotePath(config.rawDataDir())) {
                    throw new IllegalStateException(
                            "未在 " + config.rawDataDir() + " 找到源数据。请先执行 scripts/upload-to-hdfs.sh 上传数据，"
                                    + "或修改 spark-job.properties 中的 data.raw.dir。");
                }
                System.out.println("[Main] 未检测到源数据文件，自动生成模拟数据");
                MockDataGenerator.generate(config);
            } else {
                System.out.println("[Main] 数据源：" + config.orderRawFile());
            }
            // 每次作业运行清空作业日志表，便于干净地对比两种计算方式
            sink.truncate("etl_job_log");

            // ODS 原始层装载属于两种计算方式的公共前置步骤，只执行一次，
            // 不计入任何一种方式的耗时，保证性能对比的公平性。
            if (!opts.containsKey("no-ods")) {
                sink.truncate("ods_order_detail", "ods_product");
                OdsLoader.loadOrders(spark, config, currentBatchId());
                OdsLoader.loadProducts(spark, config, currentBatchId());
            }

            List<RoundSnapshot> rounds = new ArrayList<>();
            if ("df".equals(mode) || "dataframe".equals(mode) || "both".equals(mode)) {
                rounds.add(runPipeline(spark, config, sink, MODE_DATAFRAME));
            }
            if ("rdd".equals(mode) || "both".equals(mode)) {
                rounds.add(runPipeline(spark, config, sink, MODE_RDD));
            }

            // 两轮都跑完后再做一致性比对、再统一写作业日志：
            // 保证「两种实现的比较记录都保留」，且明确写出哪一轮的结果被发布给看板
            String verdict = compareRounds(rounds);
            writeJobLogs(sink, rounds, verdict);

            printComparisonSummary(sink);
            printResultPreview(sink);
        } finally {
            spark.stop();
        }

        System.out.println("\n[Main] 作业全部完成，Spark 会话已关闭");
    }

    // ==================================================================
    // 单次计算流程
    // ==================================================================

    private static RoundSnapshot runPipeline(SparkSession spark, JobConfig config, MysqlSink sink,
                                             String mode) {
        String batchId = newBatchId(mode);
        long startMs = System.currentTimeMillis();
        Timestamp startTime = new Timestamp(startMs);

        System.out.println("\n======================================================");
        System.out.println("开始执行 [" + mode + "] 计算流程，批次号：" + batchId);
        System.out.println("======================================================");

        // 清空结果表，保证作业可重复运行
        sink.truncate("dwd_order_detail", "dwd_clean_reject",
                "ads_overview", "ads_daily_trend", "ads_category_stat", "ads_product_stat",
                "ads_channel_stat", "ads_paytype_stat", "ads_region_stat",
                "ads_channel_category_stat", "ads_clean_stat");

        long rawCnt;
        long validCnt;
        long cleanDurationMs;
        long nullFieldCnt;
        long dupRecordCnt;
        long invalidTimeCnt;
        long invalidAmountCnt;
        long invalidStatusCnt;
        long missingDimCnt;
        long outputRows = 0;

        if (MODE_DATAFRAME.equals(mode)) {
            // ---------- 清洗 ----------
            DataFrameCleaner.CleanResult clean = DataFrameCleaner.clean(
                    spark, config.orderRawFile(), config.productRawFile(), batchId);
            clean.show();
            rawCnt = clean.rawCnt;
            validCnt = clean.validCnt;
            cleanDurationMs = clean.durationMs;
            nullFieldCnt = clean.nullFieldCnt;
            dupRecordCnt = clean.dupRecordCnt;
            invalidTimeCnt = clean.invalidTimeCnt;
            invalidAmountCnt = clean.invalidAmountCnt;
            invalidStatusCnt = clean.invalidStatusCnt;
            missingDimCnt = clean.missingDimCnt;

            OdsLoader.writeDwd(spark, config, clean.dwd);
            sink.insertRows("dwd_clean_reject", Schemas.REJECT_COLUMNS, clean.rejects.collectAsList());
            System.out.println("[Main] dwd_clean_reject 写入完成");

            // ---------- 多维统计 ----------
            DataFrameAnalysis.AnalysisResult analysis = DataFrameAnalysis.analyze(spark, clean.dwd, batchId);
            outputRows += TableWriter.write(sink, analysis.overview, "ads_overview", Schemas.OVERVIEW_COLUMNS);
            outputRows += TableWriter.write(sink, analysis.trend, "ads_daily_trend", Schemas.TREND_COLUMNS);
            outputRows += TableWriter.write(sink, analysis.category, "ads_category_stat", Schemas.CATEGORY_COLUMNS);
            outputRows += TableWriter.write(sink, analysis.product, "ads_product_stat", Schemas.PRODUCT_COLUMNS);
            outputRows += TableWriter.write(sink, analysis.channel, "ads_channel_stat", Schemas.CHANNEL_COLUMNS);
            outputRows += TableWriter.write(sink, analysis.paytype, "ads_paytype_stat", Schemas.PAYTYPE_COLUMNS);
            outputRows += TableWriter.write(sink, analysis.region, "ads_region_stat", Schemas.REGION_COLUMNS);
            outputRows += TableWriter.write(sink, analysis.channelCategory, "ads_channel_category_stat",
                    Schemas.CHANNEL_CATEGORY_COLUMNS);
        } else {
            // ---------- RDD 计算 ----------
            RddAnalysis.RddResult r = RddAnalysis.run(spark, config.orderRawFile(), config.productRawFile(), batchId);
            RddAnalysis.CleanStat stat = r.cleanStat;
            System.out.println("---------------- 数据清洗统计（RDD 方式） ----------------");
            System.out.println("原始记录数        : " + stat.rawCnt);
            System.out.println("R01 含空值记录    : " + stat.nullFieldCnt);
            System.out.println("R02 重复记录      : " + stat.dupRecordCnt);
            System.out.println("R03 时间异常      : " + stat.invalidTimeCnt);
            System.out.println("R04-R06 金额异常  : " + stat.invalidAmountCnt);
            System.out.println("R07 状态非法      : " + stat.invalidStatusCnt);
            System.out.println("R08 维度缺失      : " + stat.missingDimCnt);
            System.out.println("丢弃记录数        : " + stat.discardCnt);
            System.out.println("有效记录数        : " + stat.validCnt);
            System.out.printf("数据质量得分      : %.4f%n", stat.qualityScore());
            System.out.println("清洗耗时(ms)      : " + stat.durationMs);
            System.out.println("---------------------------------------------------------");

            rawCnt = stat.rawCnt;
            validCnt = stat.validCnt;
            cleanDurationMs = stat.durationMs;
            nullFieldCnt = stat.nullFieldCnt;
            dupRecordCnt = stat.dupRecordCnt;
            invalidTimeCnt = stat.invalidTimeCnt;
            invalidAmountCnt = stat.invalidAmountCnt;
            invalidStatusCnt = stat.invalidStatusCnt;
            missingDimCnt = stat.missingDimCnt;

            Dataset<Row> dwd = spark.createDataFrame(r.dwd, Schemas.dwdSchema());
            OdsLoader.writeDwd(spark, config, dwd);
            // RDD 实现同样必须把「被丢弃的异常记录」落到留痕表，
            // 否则两种实现的产物不可比（早期版本只写了 DataFrame 那一轮）
            // 注意：r.rejects 是 JavaRDD，取值用 collect()（不是 Dataset 的 collectAsList()）
            sink.insertRows("dwd_clean_reject", Schemas.REJECT_COLUMNS, r.rejects.collect());
            System.out.println("[Main] dwd_clean_reject 写入完成（RDD 方式）");
            outputRows += TableWriter.write(sink, r.overview, "ads_overview", Schemas.OVERVIEW_COLUMNS);
            outputRows += TableWriter.write(sink, r.trend, "ads_daily_trend", Schemas.TREND_COLUMNS);
            outputRows += TableWriter.write(sink, r.category, "ads_category_stat", Schemas.CATEGORY_COLUMNS);
            outputRows += TableWriter.write(sink, r.product, "ads_product_stat", Schemas.PRODUCT_COLUMNS);
            outputRows += TableWriter.write(sink, r.channel, "ads_channel_stat", Schemas.CHANNEL_COLUMNS);
            outputRows += TableWriter.write(sink, r.paytype, "ads_paytype_stat", Schemas.PAYTYPE_COLUMNS);
            outputRows += TableWriter.write(sink, r.region, "ads_region_stat", Schemas.REGION_COLUMNS);
            outputRows += TableWriter.write(sink, r.channelCategory, "ads_channel_category_stat",
                    Schemas.CHANNEL_CATEGORY_COLUMNS);
        }

        long durationMs = System.currentTimeMillis() - startMs;
        Timestamp endTime = new Timestamp(System.currentTimeMillis());

        // ---------- 清洗质量统计落库 ----------
        // 丢弃记录数只统计 R03/R04/R05 命中的记录；
        // 重复记录数（R02）单独统计，三者满足：raw = dup + discard + valid
        long realDiscardCnt = rawCnt - dupRecordCnt - validCnt;
        List<Object[]> cleanRows = new ArrayList<>();
        cleanRows.add(new Object[]{
                batchId, new java.sql.Date(System.currentTimeMillis()),
                rawCnt, nullFieldCnt, dupRecordCnt, invalidTimeCnt,
                invalidAmountCnt, invalidStatusCnt, missingDimCnt,
                validCnt, realDiscardCnt,
                BigDecimal.valueOf(rawCnt == 0 ? 0d : (double) validCnt / rawCnt).setScale(4, BigDecimal.ROUND_HALF_UP),
                cleanDurationMs, endTime
        });
        sink.insertObjects("ads_clean_stat", CLEAN_STAT_COLUMNS, cleanRows);

        // 作业日志**不在这里落库**：两轮都跑时要等两轮结束、做过一致性比对后再统一写入，
        // 这样第二轮不会覆盖第一轮的比较记录，日志里也能带上「发布方」与比对结论。
        RoundSnapshot snap = new RoundSnapshot();
        snap.mode = mode;
        snap.batchId = batchId;
        snap.startTime = startTime;
        snap.endTime = endTime;
        snap.durationMs = durationMs;
        snap.rawCnt = rawCnt;
        snap.validCnt = validCnt;
        snap.outputRows = outputRows;
        snap.cleanDurationMs = cleanDurationMs;
        // 本轮自己的产物快照 —— 必须现在取，因为下一轮会先 TRUNCATE 掉这些表
        snap.dwdRows = scalarLong(sink, "SELECT COUNT(*) FROM dwd_order_detail");
        snap.rejectRows = scalarLong(sink, "SELECT COUNT(*) FROM dwd_clean_reject");
        snap.adsGmv = scalarDecimal(sink, "SELECT IFNULL(SUM(gmv),0) FROM ads_daily_trend");
        snap.adsValidOrderCnt = scalarLong(sink,
                "SELECT IFNULL(SUM(valid_order_cnt),0) FROM ads_daily_trend");

        System.out.println("[" + mode + "] 流程执行完成，总耗时 " + durationMs + " ms"
                + "（本轮产物快照：DWD " + snap.dwdRows + " 行 / 异常 " + snap.rejectRows
                + " 行 / ADS 趋势 GMV " + snap.adsGmv.toPlainString() + "）");
        return snap;
    }

    // ==================================================================
    // 两轮结果比对与作业日志
    // ==================================================================

    /**
     * 两轮实现结果一致性比对。
     *
     * <p>每一轮都会先 TRUNCATE 再重写 DWD / ADS，所以<b>最后跑的那一轮才是看板看到的结果</b>。
     * 这里把两轮各自的产物快照拿出来对比，明确回答两个问题：
     * 「发布方是谁」以及「两轮结果是否一致」，避免第二轮无意清空第一轮却没人发现。</p>
     *
     * @return 一致性结论：单轮返回说明文本，两轮返回 PASS / FAIL
     */
    private static String compareRounds(List<RoundSnapshot> rounds) {
        if (rounds.size() < 2) {
            return "仅执行单轮（" + rounds.get(0).mode + "），无跨轮比对";
        }
        RoundSnapshot a = rounds.get(0);
        RoundSnapshot b = rounds.get(1);
        boolean sameRows = a.dwdRows == b.dwdRows && a.rejectRows == b.rejectRows;
        boolean sameGmv = a.adsGmv.compareTo(b.adsGmv) == 0;
        boolean sameOrders = a.adsValidOrderCnt == b.adsValidOrderCnt;
        boolean ok = sameRows && sameGmv && sameOrders;

        System.out.println("\n======================================================");
        System.out.println("  两轮实现结果一致性比对");
        System.out.println("======================================================");
        System.out.printf("%-12s %-14s %-14s %-18s %-14s%n",
                "轮次", "DWD 行数", "异常行数", "ADS 趋势 GMV", "有效订单量");
        for (RoundSnapshot s : rounds) {
            System.out.printf("%-12s %-14d %-14d %-18s %-14d%n",
                    s.mode, s.dwdRows, s.rejectRows, s.adsGmv.toPlainString(), s.adsValidOrderCnt);
        }
        System.out.println("发布方（看板展示的结果）=" + rounds.get(rounds.size() - 1).mode
                + "（最后写入的一轮）");
        if (ok) {
            System.out.println("结论：PASS —— 两种实现的 DWD 行数 / 异常行数 / ADS GMV / 有效订单量完全一致");
        } else {
            System.out.println("结论：FAIL —— 两种实现结果不一致，本轮结果不可直接对外使用！");
            if (!sameRows) {
                System.out.println("  差异：DWD 或异常行数不一致（"
                        + a.mode + " DWD=" + a.dwdRows + " 异常=" + a.rejectRows + " / "
                        + b.mode + " DWD=" + b.dwdRows + " 异常=" + b.rejectRows + "）");
            }
            if (!sameGmv) {
                System.out.println("  差异：ADS 趋势 GMV 不一致（"
                        + a.mode + "=" + a.adsGmv.toPlainString()
                        + " / " + b.mode + "=" + b.adsGmv.toPlainString() + "）");
            }
            if (!sameOrders) {
                System.out.println("  差异：ADS 有效订单量不一致（"
                        + a.mode + "=" + a.adsValidOrderCnt + " / " + b.mode + "=" + b.adsValidOrderCnt + "）");
            }
        }
        return ok ? "PASS" : "FAIL";
    }

    /**
     * 统一写作业日志。
     *
     * <p>两轮的记录在这里<b>一次性写入</b>，因此第二轮不可能覆盖第一轮的比较记录；
     * 同时把「本轮回合是否为发布方」与「跨轮比对结论」写进 remark，
     * 让看板/监控页能直接读懂哪一轮的结果在用、是否可信。</p>
     */
    private static void writeJobLogs(MysqlSink sink, List<RoundSnapshot> rounds, String verdict) {
        RoundSnapshot published = rounds.get(rounds.size() - 1);
        List<Object[]> logRows = new ArrayList<>();
        for (RoundSnapshot s : rounds) {
            boolean isPublished = (s == published);
            String remark = "清洗耗时 " + s.cleanDurationMs + "ms；统计与落库耗时 "
                    + (s.durationMs - s.cleanDurationMs) + "ms；ODS 装载为公共前置步骤，不计入本方式耗时"
                    + "；结果发布：" + (isPublished
                            ? "是（本轮最后写入，DWD/ADS 即看板展示的结果）"
                            : "否（对照轮，结果已被后续轮次覆盖）")
                    + "；跨轮比对：" + verdict;
            logRows.add(new Object[]{
                    s.batchId, JOB_NAME, s.mode, s.startTime, s.endTime, s.durationMs,
                    s.rawCnt, s.validCnt + s.outputRows,
                    "FAIL".equals(verdict) ? "WARN" : "SUCCESS",
                    remark});
        }
        sink.insertObjects("etl_job_log", JOB_LOG_COLUMNS, logRows);
    }

    /** 单轮计算的产物快照：用于两轮比对与作业日志落库 */
    private static class RoundSnapshot {
        String mode;
        String batchId;
        Timestamp startTime;
        Timestamp endTime;
        long durationMs;
        long rawCnt;
        long validCnt;
        long outputRows;
        long cleanDurationMs;
        /** 本轮写完后 DWD 的行数 */
        long dwdRows;
        /** 本轮写完后异常留痕表的行数 */
        long rejectRows;
        /** 本轮写完后 ADS 趋势表的 GMV 合计 */
        BigDecimal adsGmv;
        /** 本轮写完后 ADS 趋势表的有效订单量合计 */
        long adsValidOrderCnt;
    }

    private static long scalarLong(MysqlSink sink, String sql) {
        List<Object[]> rows = query(sink, sql);
        if (rows.isEmpty() || rows.get(0)[0] == null) {
            return 0L;
        }
        return (long) Double.parseDouble(String.valueOf(rows.get(0)[0]));
    }

    private static BigDecimal scalarDecimal(MysqlSink sink, String sql) {
        List<Object[]> rows = query(sink, sql);
        if (rows.isEmpty() || rows.get(0)[0] == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(String.valueOf(rows.get(0)[0]));
    }

    // ==================================================================
    // 输出与工具
    // ==================================================================

    private static SparkSession buildSparkSession(JobConfig config) {
        SparkSession.Builder builder = SparkSession.builder()
                .appName(config.sparkAppName())
                .config("spark.ui.enabled", config.getBoolean("spark.ui.enabled", false))
                .config("spark.sql.shuffle.partitions", String.valueOf(config.shufflePartitions()))
                .config("spark.driver.maxResultSize", config.get("spark.driver.maxResultSize", "512m"))
                .config("spark.sql.session.timeZone", "Asia/Shanghai")
                .config("spark.serializer", "org.apache.spark.serializer.KryoSerializer");

        // 单机模式显式指定 master；集群模式留空，由 spark-submit --master 决定
        if (!config.masterManagedBySubmit()) {
            builder.master(config.sparkMaster());
        }
        return builder.getOrCreate();
    }

    /** 判断路径是否位于远端文件系统（HDFS 等），用于区分"本地生成数据"与"需要先上传 HDFS" */
    private static boolean isRemotePath(String path) {
        if (path == null) {
            return false;
        }
        String p = path.trim().toLowerCase();
        return p.startsWith("hdfs://") || p.startsWith("s3a://") || p.startsWith("s3://")
                || p.startsWith("oss://") || p.startsWith("viewfs://");
    }

    /** 使用 Hadoop FileSystem API 判断路径是否存在，同时兼容本地与 HDFS */
    private static boolean pathExists(String path) {
        try {
            org.apache.hadoop.conf.Configuration conf = new org.apache.hadoop.conf.Configuration();
            org.apache.hadoop.fs.Path p = new org.apache.hadoop.fs.Path(path);
            org.apache.hadoop.fs.FileSystem fs = p.getFileSystem(conf);
            return fs.exists(p);
        } catch (Exception e) {
            System.err.println("[Main] 路径检查失败: " + path + " -> " + e.getMessage());
            return false;
        }
    }

    private static String newBatchId(String mode) {
        String ts = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
        return "B" + ts + "-" + (MODE_DATAFRAME.equals(mode) ? "DF" : "RDD");
    }

    /** ODS 原始层装载使用的批次号（两种计算方式共用同一份原始数据） */
    private static String currentBatchId() {
        return "B" + new SimpleDateFormat("yyyyMMddHHmmss").format(new Date()) + "-ODS";
    }

    /** 两种计算方式的性能对比 */
    private static void printComparisonSummary(MysqlSink sink) {
        System.out.println("\n======================================================");
        System.out.println("  两种计算方式运行效果对比（etl_job_log）");
        System.out.println("======================================================");
        String sql = "SELECT compute_mode, duration_ms, input_rows, job_status "
                + "FROM etl_job_log ORDER BY id";
        List<Object[]> rows = query(sink, sql);
        System.out.printf("%-12s %-14s %-14s %-10s%n", "计算方式", "总耗时(ms)", "输入记录数", "状态");
        long dfMs = -1, rddMs = -1;
        for (Object[] r : rows) {
            System.out.printf("%-12s %-14s %-14s %-10s%n", r[0], r[1], r[2], r[3]);
            if (MODE_DATAFRAME.equals(String.valueOf(r[0]))) {
                dfMs = Long.parseLong(String.valueOf(r[1]));
            } else {
                rddMs = Long.parseLong(String.valueOf(r[1]));
            }
        }
        if (dfMs > 0 && rddMs > 0) {
            long diff = rddMs - dfMs;
            double pct = dfMs == 0 ? 0 : diff * 100.0 / dfMs;
            System.out.printf("%n结论：DataFrame 方式耗时 %d ms，RDD 方式耗时 %d ms，RDD 比 DataFrame %s %.2f%%%n",
                    dfMs, rddMs, diff > 0 ? "慢" : "快", Math.abs(pct));
        }
    }

    /** 关键结果抽查，便于运行后快速确认数据正确性 */
    private static void printResultPreview(MysqlSink sink) {
        System.out.println("\n======================================================");
        System.out.println("  核心指标抽查（ads_overview）");
        System.out.println("======================================================");
        List<Object[]> kpis = query(sink,
                "SELECT kpi_name, kpi_value, kpi_unit FROM ads_overview ORDER BY id");
        for (Object[] r : kpis) {
            System.out.printf("%-14s %-18s %s%n", r[0], r[1], r[2] == null ? "" : r[2]);
        }

        System.out.println("\n======================================================");
        System.out.println("  销售趋势抽查（ads_daily_trend 最近 5 天）");
        System.out.println("======================================================");
        List<Object[]> trend = query(sink,
                "SELECT stat_date, valid_order_cnt, gmv, buyer_cnt, refund_rate "
                        + "FROM ads_daily_trend ORDER BY stat_date DESC LIMIT 5");
        System.out.printf("%-14s %-14s %-16s %-12s %-10s%n", "日期", "有效订单量", "GMV", "用户数", "退款率");
        for (Object[] r : trend) {
            System.out.printf("%-14s %-14s %-16s %-12s %-10s%n", r[0], r[1], r[2], r[3], r[4]);
        }
    }

    private static List<Object[]> query(MysqlSink sink, String sql) {
        List<Object[]> rows = new ArrayList<>();
        try (Connection c = sink.open(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            while (rs.next()) {
                Object[] row = new Object[n];
                for (int i = 1; i <= n; i++) {
                    row[i - 1] = rs.getObject(i);
                }
                rows.add(row);
            }
        } catch (Exception e) {
            System.err.println("[Main] 查询失败: " + sql + " -> " + e.getMessage());
        }
        return rows;
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> opts = new LinkedHashMap<>();
        for (String arg : args) {
            if (arg == null || arg.trim().isEmpty()) {
                continue;
            }
            String a = arg.trim();
            if (a.startsWith("--")) {
                a = a.substring(2);
            }
            int eq = a.indexOf('=');
            if (eq > 0) {
                opts.put(a.substring(0, eq).toLowerCase(), a.substring(eq + 1));
            } else {
                opts.put(a.toLowerCase(), "true");
            }
        }
        return opts;
    }
}
