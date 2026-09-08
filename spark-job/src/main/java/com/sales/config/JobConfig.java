package com.sales.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * 作业配置加载器。
 *
 * <p>加载优先级（由低到高）：</p>
 * <ol>
 *   <li>classpath 下的 spark-job.properties（默认值）</li>
 *   <li>外部配置文件，通过 JVM 参数 -Dconfig.file=xxx.properties 指定</li>
 *   <li>JVM 系统属性 -Dkey=value</li>
 * </ol>
 */
public class JobConfig {

    private static final String DEFAULT_RESOURCE = "spark-job.properties";

    private final Properties props = new Properties();

    private JobConfig() {
    }

    public static JobConfig load() {
        JobConfig cfg = new JobConfig();
        cfg.loadFromClasspath();
        cfg.loadFromExternalFile(System.getProperty("config.file"));
        return cfg;
    }

    private void loadFromClasspath() {
        try (InputStream in = JobConfig.class.getClassLoader().getResourceAsStream(DEFAULT_RESOURCE)) {
            if (in != null) {
                props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取类路径配置文件失败: " + DEFAULT_RESOURCE, e);
        }
    }

    private void loadFromExternalFile(String path) {
        if (path == null || path.trim().isEmpty()) {
            return;
        }
        File f = new File(path);
        if (!f.exists()) {
            throw new IllegalArgumentException("指定的配置文件不存在: " + f.getAbsolutePath());
        }
        try (InputStream in = new FileInputStream(f)) {
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("读取外部配置文件失败: " + f.getAbsolutePath(), e);
        }
    }

    public String get(String key, String defaultValue) {
        String sys = System.getProperty(key);
        if (sys != null && !sys.trim().isEmpty()) {
            return sys.trim();
        }
        return props.getProperty(key, defaultValue);
    }

    public String get(String key) {
        return get(key, null);
    }

    public int getInt(String key, int defaultValue) {
        String v = get(key);
        return (v == null || v.trim().isEmpty()) ? defaultValue : Integer.parseInt(v.trim());
    }

    public long getLong(String key, long defaultValue) {
        String v = get(key);
        return (v == null || v.trim().isEmpty()) ? defaultValue : Long.parseLong(v.trim());
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String v = get(key);
        return (v == null || v.trim().isEmpty()) ? defaultValue : Boolean.parseBoolean(v.trim());
    }

    // ------------------------------------------------------------------
    // 常用配置项的语义化读取
    // ------------------------------------------------------------------

    /**
     * 模拟数据输出目录（必须是本地路径或本地共享目录）。
     *
     * <p>数据生成依赖 java.io，因此只能在本地文件系统写入；
     * 生成完成后由部署脚本上传到 HDFS，供集群模式读取。</p>
     */
    public String mockOutputDir() {
        return get("mock.output.dir", "data/raw");
    }

    /** 原始数据目录：可以是本地路径，也可以是 HDFS 路径（如 hdfs://node1:8020/sales/raw） */
    public String rawDataDir() {
        return get("data.raw.dir", "data/raw");
    }

    public String orderRawFile() {
        return get("data.raw.order.file", joinPath(rawDataDir(), "ods_order_detail.csv"));
    }

    public String productRawFile() {
        return get("data.raw.product.file", joinPath(rawDataDir(), "ods_product.csv"));
    }

    private static String joinPath(String dir, String name) {
        if (dir.endsWith("/")) {
            return dir + name;
        }
        return dir + "/" + name;
    }

    /** 模拟订单明细行数 */
    public int mockRowCount() {
        return getInt("mock.row.count", 200000);
    }

    public int mockProductCount() {
        return getInt("mock.product.count", 200);
    }

    public long mockSeed() {
        return getLong("mock.seed", 20260907L);
    }

    /** 数据起始日期 */
    public String dataStartDate() {
        return get("data.start.date", "2026-06-01");
    }

    /** 数据结束日期 */
    public String dataEndDate() {
        return get("data.end.date", "2026-08-30");
    }

    public String sparkMaster() {
        // 留空表示不指定 master，交由 spark-submit --master 参数决定（集群提交推荐方式）
        return get("spark.master", "local[*]");
    }

    /** 未显式配置 master 时返回 true（此时由 spark-submit 决定运行模式） */
    public boolean masterManagedBySubmit() {
        String v = get("spark.master", "");
        return v == null || v.trim().isEmpty();
    }

    public String sparkAppName() {
        return get("spark.app.name", "spark-sales-analysis");
    }

    public int shufflePartitions() {
        return getInt("spark.sql.shuffle.partitions", Math.max(2, Runtime.getRuntime().availableProcessors()));
    }

    public int topN() {
        return getInt("analysis.topN", 10);
    }

    public String jdbcUrl() {
        return get("jdbc.url",
                "jdbc:mysql://127.0.0.1:3306/sales_analysis?useUnicode=true&characterEncoding=utf8&useSSL=false");
    }

    public String jdbcUser() {
        return get("jdbc.user", "root");
    }

    public String jdbcPassword() {
        return get("jdbc.password", "123456");
    }

    /** 按天运行还是整体运行（true=把整段数据视作一个统计日，便于演示） */
    public boolean aggregateWholePeriod() {
        return getBoolean("analysis.aggregate.whole.period", false);
    }

    /**
     * Hadoop 运行目录（Windows 下 Spark 需要 winutils.exe）。
     * 优先取配置项 hadoop.home.dir，其次取环境变量 HADOOP_HOME。
     */
    public String hadoopHomeDir() {
        String cfg = get("hadoop.home.dir");
        if (cfg != null && !cfg.trim().isEmpty()) {
            return cfg.trim();
        }
        String env = System.getenv("HADOOP_HOME");
        if (env != null && !env.trim().isEmpty()) {
            return env.trim();
        }
        return "";
    }

    /** 把 hadoop.home.dir 写入系统属性，必须在 SparkSession 创建之前调用 */
    public void prepareHadoopHome() {
        String home = hadoopHomeDir();
        if (!home.isEmpty()) {
            System.setProperty("hadoop.home.dir", home);
        }
    }

    public Properties asProperties() {
        Properties copy = new Properties();
        copy.putAll(props);
        return copy;
    }
}
