package com.sales.generator;

import com.sales.common.Schemas;
import com.sales.config.JobConfig;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.expressions.Window;
import org.apache.spark.sql.expressions.WindowSpec;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;

import java.io.IOException;
import java.util.Locale;

import static org.apache.spark.sql.functions.coalesce;
import static org.apache.spark.sql.functions.col;
import static org.apache.spark.sql.functions.count;
import static org.apache.spark.sql.functions.first;
import static org.apache.spark.sql.functions.lit;
import static org.apache.spark.sql.functions.row_number;
import static org.apache.spark.sql.functions.trim;
import static org.apache.spark.sql.functions.when;

/**
 * 真实数据集适配器：把 <b>Olist 巴西电商公开数据集</b> 的多张原始表
 * 转换为本项目的标准 ODS 格式（与模拟数据完全同构，因此下游清洗 / 统计 / 落库代码零改动）。
 *
 * <p>数据来源：Brazilian E-Commerce Public Dataset by Olist（2016-09 ~ 2018-10，约 10 万笔真实订单）。
 * 原始文件需先用 {@code scripts/download-real-data.ps1}（Windows）或
 * {@code scripts/linux/download-real-data.sh}（Linux）下载到 {@code data/real-raw/}。</p>
 *
 * <h3>字段映射关系</h3>
 * <table border="1">
 *   <tr><th>本项目 ODS 字段</th><th>Olist 来源</th><th>说明</th></tr>
 *   <tr><td>order_id</td><td>orders.order_id</td><td>直接映射</td></tr>
 *   <tr><td>order_time</td><td>orders.order_purchase_timestamp</td><td>下单时间</td></tr>
 *   <tr><td>user_id</td><td>customers.customer_unique_id</td><td><b>用真实用户 ID</b>（96,096 个，存在复购）</td></tr>
 *   <tr><td>product_id</td><td>order_items.product_id</td><td>直接映射</td></tr>
 *   <tr><td>channel</td><td>—— 数据源无此字段</td><td>固定填 "Olist商城"（该数据集为单渠道市场）</td></tr>
 *   <tr><td>province</td><td>customers.customer_state</td><td>巴西 27 个州</td></tr>
 *   <tr><td>city</td><td>customers.customer_city</td><td>4,119 个城市</td></tr>
 *   <tr><td>quantity</td><td>order_items 行数聚合</td><td>同一订单同一商品出现多行即为多件</td></tr>
 *   <tr><td>unit_price</td><td>order_items.price</td><td>单价（不含运费）</td></tr>
 *   <tr><td>discount_amount</td><td>—— 数据源无此字段</td><td>固定填 0.00（Olist 的 price 已是成交价）</td></tr>
 *   <tr><td>pay_amount</td><td>price × quantity</td><td>实付金额；<b>运费 freight_value 不计入</b>（口径见 docs/02）</td></tr>
 *   <tr><td>order_status</td><td>orders.order_status</td><td>英文状态映射为中文（见 {@link #mapOrderStatus()}）</td></tr>
 *   <tr><td>pay_type</td><td>order_payments.payment_type</td><td>一单多笔支付时取金额最大的一笔</td></tr>
 * </table>
 *
 * <p>商品维度表：Olist 无商品名称 / 品牌 / 成本价，分别用"类目英文名+商品编号"、
 * "未知"、0 填充（其中品牌会由清洗规则 R01 正常处理，成本价不参与任何指标计算）。</p>
 */
public final class RealDataImporter {

    // ---------- 原始文件字段（顺序必须与 CSV 表头一致） ----------
    private static final String[] ORDERS_FIELDS = {
            "order_id", "customer_id", "order_status", "order_purchase_timestamp",
            "order_approved_at", "order_delivered_carrier_date",
            "order_delivered_customer_date", "order_estimated_delivery_date"};

    private static final String[] ITEMS_FIELDS = {
            "order_id", "order_item_id", "product_id", "seller_id",
            "shipping_limit_date", "price", "freight_value"};

    private static final String[] CUSTOMERS_FIELDS = {
            "customer_id", "customer_unique_id", "customer_zip_code_prefix",
            "customer_city", "customer_state"};

    private static final String[] PRODUCTS_FIELDS = {
            "product_id", "product_category_name", "product_name_lenght",
            "product_description_lenght", "product_photos_qty", "product_weight_g",
            "product_length_cm", "product_height_cm", "product_width_cm"};

    private static final String[] PAYMENTS_FIELDS = {
            "order_id", "payment_sequential", "payment_type",
            "payment_installments", "payment_value"};

    private static final String[] CATEGORY_TRANS_FIELDS = {
            "product_category_name", "product_category_name_english"};

    /** 原始文件名 */
    private static final String F_ORDERS = "olist_orders_dataset.csv";
    private static final String F_ITEMS = "olist_order_items_dataset.csv";
    private static final String F_CUSTOMERS = "olist_customers_dataset.csv";
    private static final String F_PRODUCTS = "olist_products_dataset.csv";
    private static final String F_PAYMENTS = "olist_order_payments_dataset.csv";
    private static final String F_CAT_TRANS = "product_category_name_translation.csv";

    private RealDataImporter() {
    }

    /**
     * 执行导入：读 Olist 原始表 → 输出本项目的 ODS 标准 CSV。
     *
     * @param config 作业配置（读取 real.raw.dir / real.ods.dir）
     */
    public static void importToOds(JobConfig config) throws IOException {
        String rawDir = config.get("real.raw.dir", "data/real-raw");
        String outDir = config.get("real.ods.dir", "data/real");
        int partitions = config.getInt("spark.sql.shuffle.partitions", 4);

        System.out.println("======================================================");
        System.out.println("  真实数据集导入（Olist 巴西电商公开数据集）");
        System.out.println("======================================================");
        System.out.println("[RealImport] 原始数据目录 : " + rawDir);
        System.out.println("[RealImport] 输出 ODS 目录: " + outDir);

        SparkSession spark = buildSparkSession(config, partitions);
        try {
            // ---------- 1. 读取 6 张原始表（全部按字符串读入，与 ODS 层保持一致） ----------
            Dataset<Row> orders = readCsv(spark, rawDir + "/" + F_ORDERS, ORDERS_FIELDS);
            Dataset<Row> items = readCsv(spark, rawDir + "/" + F_ITEMS, ITEMS_FIELDS);
            Dataset<Row> customers = readCsv(spark, rawDir + "/" + F_CUSTOMERS, CUSTOMERS_FIELDS);
            Dataset<Row> products = readCsv(spark, rawDir + "/" + F_PRODUCTS, PRODUCTS_FIELDS);
            Dataset<Row> payments = readCsv(spark, rawDir + "/" + F_PAYMENTS, PAYMENTS_FIELDS);
            Dataset<Row> catTrans = readCsv(spark, rawDir + "/" + F_CAT_TRANS, CATEGORY_TRANS_FIELDS);

            System.out.printf("[RealImport] 原始行数：订单 %d / 订单商品 %d / 客户 %d / 商品 %d / 支付 %d%n",
                    orders.count(), items.count(), customers.count(),
                    products.count(), payments.count());

            // ---------- 2. 支付方式：一个订单可能有多笔支付，取金额最大的一笔作为主支付方式 ----------
            WindowSpec payWindow = Window.partitionBy(col("order_id"))
                    .orderBy(col("payment_value").cast(DataTypes.DoubleType).desc());
            Dataset<Row> payDim = payments
                    .withColumn("rn", row_number().over(payWindow))
                    .filter(col("rn").equalTo(1))
                    .select(col("order_id"), col("payment_type"));

            // ---------- 3. 商品维度：类目名翻译成英文（Olist 原始为葡萄牙语） ----------
            // 两张表都有 product_category_name 列，必须先用别名限定，否则会报字段歧义
            Dataset<Row> productsA = products.alias("p");
            Dataset<Row> catTransA = catTrans.alias("c");
            Dataset<Row> prodBase = productsA
                    .join(catTransA, col("p.product_category_name")
                            .equalTo(col("c.product_category_name")), "left_outer")
                    .select(col("p.product_id"),
                            trim(coalesce(col("c.product_category_name_english"),
                                    col("p.product_category_name"))).as("category_name"))
                    // 类目缺失（610 个商品）统一占位，保证后面能稳定生成编码
                    .withColumn("category_name",
                            when(col("category_name").isNull()
                                            .or(trim(col("category_name")).equalTo(lit(""))),
                                    lit(Schemas.UNKNOWN_CATEGORY))
                                    .otherwise(col("category_name")));

            // Olist 只提供类目名、没有类目编号，而类目名最长 45 字符（超过 category_id 列宽 32）。
            // 这里按名称排序生成稳定的可读编码 CAT001、CAT002...（类目只有 70 多个，可在 Driver 端处理）
            java.util.List<Row> catRows = prodBase.select("category_name")
                    .distinct().orderBy("category_name").collectAsList();
            java.util.List<Row> catIdRows = new java.util.ArrayList<>();
            int catSeq = 1;
            for (Row r : catRows) {
                catIdRows.add(RowFactory.create(r.getString(0), String.format("CAT%03d", catSeq++)));
            }
            StructType catIdSchema = new StructType()
                    .add("m_name", DataTypes.StringType, false)
                    .add("m_id", DataTypes.StringType, false);
            Dataset<Row> catIdMap = spark.createDataFrame(catIdRows, catIdSchema);
            Dataset<Row> prodDim = prodBase
                    .join(catIdMap, prodBase.col("category_name").equalTo(catIdMap.col("m_name")), "left")
                    .select(col("product_id"), col("category_name"), col("m_id").as("category_id"));
            System.out.println("[RealImport] 类目编码表已生成：" + catIdRows.size() + " 个类目");

            // ---------- 4. 订单商品明细：同一订单内的同一商品合并计件 ----------
            Dataset<Row> itemAgg = items.groupBy("order_id", "product_id").agg(
                    count(col("order_item_id")).as("quantity"),
                    first(col("price"), true).as("unit_price"));

            // ---------- 5. 拼成订单明细宽表 ----------
            Dataset<Row> wide = itemAgg
                    .join(orders, new String[]{"order_id"}, "inner")
                    .join(customers, new String[]{"customer_id"}, "inner")
                    .join(prodDim, new String[]{"product_id"}, "left")
                    .join(payDim, new String[]{"order_id"}, "left");

            // ---------- 6. 输出为标准 ODS 订单明细（13 个业务字段，顺序与 ORDER_RAW_FIELDS 一致） ----------
            Dataset<Row> orderOut = wide.select(
                    col("order_id"),
                    col("order_purchase_timestamp").as("order_time"),
                    col("customer_unique_id").as("user_id"),
                    col("product_id"),
                    lit("Olist商城").as("channel"),
                    col("customer_state").as("province"),
                    col("customer_city").as("city"),
                    col("quantity"),
                    col("unit_price"),
                    lit("0.00").as("discount_amount"),
                    // 实付金额 = 单价 × 数量（运费不计入，口径见 docs/02）
                    org.apache.spark.sql.functions.round(
                                    col("unit_price").cast(DataTypes.createDecimalType(20, 4))
                                            .multiply(col("quantity").cast(DataTypes.createDecimalType(20, 4))), 2)
                            .cast(DataTypes.createDecimalType(14, 2)).as("pay_amount"),
                    mapOrderStatus().as("order_status"),
                    mapPayType().as("pay_type"));

            // 类目为空的上游商品：留空由清洗规则 R08 填充为"未知类目"
            Dataset<Row> productOut = prodDim.select(
                    col("product_id"),
                    // Olist 不提供商品名称，用「类目名-商品编号前 8 位」组成可读标识
                    when(col("category_name").isNull().or(trim(col("category_name")).equalTo(lit(""))),
                            lit(Schemas.UNKNOWN_PRODUCT))
                            .otherwise(org.apache.spark.sql.functions.concat(
                                    col("category_name"), lit("-"),
                                    org.apache.spark.sql.functions.substring(col("product_id"), 1, 8)))
                            .as("product_name"),
                    coalesce(col("category_id"), lit(Schemas.UNKNOWN_CATEGORY_ID)).as("category_id"),
                    coalesce(col("category_name"), lit(Schemas.UNKNOWN_CATEGORY)).as("category_name"),
                    lit(Schemas.UNKNOWN).as("brand"),
                    lit("0.00").as("cost_price"),
                    lit("").as("launch_date"));

            // ---------- 7. 写出（单文件 + 表头，文件名与本项目约定一致） ----------
            writeSingleCsv(orderOut, outDir, "ods_order_detail.csv");
            writeSingleCsv(productOut, outDir, "ods_product.csv");

            System.out.println("[RealImport] 订单明细行数 : " + orderOut.count());
            System.out.println("[RealImport] 商品维度行数 : " + productOut.count());
            System.out.println("[RealImport] 导入完成，输出目录：" + outDir);
            System.out.println("[RealImport] 下一步：把 spark-job.properties 的 data.raw.dir 设为 "
                    + outDir + " 后执行 --mode=both");
        } finally {
            spark.stop();
        }
    }

    /** Olist 订单状态 → 本项目中文状态枚举 */
    private static Column mapOrderStatus() {
        return when(col("order_status").equalTo("delivered"), lit("已完成"))
                // 已发货 / 已开票 / 处理中 / 已创建 / 已批准：都表示「已付款成功、尚未完成」
                .when(col("order_status").isin(
                        "shipped", "invoiced", "processing", "created", "approved"), lit("已支付"))
                .when(col("order_status").equalTo("canceled"), lit("已取消"))
                // 无法履约 → 实际业务中会退款
                .when(col("order_status").equalTo("unavailable"), lit("已退款"))
                // 兜底：交给清洗规则 R07 判定为非法状态
                .otherwise(lit("其他"));
    }

    /** Olist 支付方式 → 中文 */
    private static Column mapPayType() {
        return when(col("payment_type").equalTo("credit_card"), lit("信用卡"))
                .when(col("payment_type").equalTo("debit_card"), lit("借记卡"))
                .when(col("payment_type").equalTo("boleto"), lit("Boleto票据"))
                .when(col("payment_type").equalTo("voucher"), lit("代金券"))
                .when(col("payment_type").equalTo("not_defined"), lit("未知"))
                .otherwise(nullableUnknown());
    }

    /** 支付方式缺失时置空，交由清洗规则 R01 填充为"未知" */
    private static Column nullableUnknown() {
        return lit(null).cast(DataTypes.StringType);
    }

    private static Dataset<Row> readCsv(SparkSession spark, String path, String[] fields) {
        StructType schema = new StructType();
        for (String f : fields) {
            schema = schema.add(f, DataTypes.StringType, true);
        }
        return spark.read()
                .option("header", "true")
                .option("encoding", "UTF-8")
                .option("mode", "PERMISSIVE")
                .schema(schema)
                .csv(path);
    }

    /**
     * 写出单个 CSV 文件（Spark 原生写出的是目录 + part 文件，
     * 这里用 Hadoop FileSystem API 把 part 文件改名成项目约定的文件名）。
     */
    private static void writeSingleCsv(Dataset<Row> df, String outDir, String fileName) throws IOException {
        String tmpDir = outDir + "/_tmp_" + fileName.replace(".csv", "")
                + "_" + Long.toHexString(System.nanoTime());
        df.coalesce(1).write()
                .option("header", "true")
                .option("encoding", "UTF-8")
                .option("emptyValue", "")
                .mode("overwrite")
                .csv(tmpDir);

        Configuration conf = new Configuration();
        FileSystem fs = FileSystem.get(conf);
        Path tmpPath = new Path(tmpDir);
        Path target = new Path(outDir + "/" + fileName);
        boolean moved = false;
        for (FileStatus st : fs.listStatus(tmpPath)) {
            String name = st.getPath().getName();
            if (name.startsWith("part-") && name.endsWith(".csv")) {
                if (fs.exists(target)) {
                    fs.delete(target, false);
                }
                moved = fs.rename(st.getPath(), target);
                break;
            }
        }
        if (!moved) {
            throw new IOException("未能生成目标文件：" + target);
        }
        try {
            fs.delete(tmpPath, true);
        } catch (IOException ignore) {
            // 临时目录清理失败不影响主流程（例如 Windows 上偶发文件占用）
        }
        System.out.println("[RealImport] 已写出 " + target + "  （"
                + fs.getFileStatus(target).getLen() + " 字节）");
    }

    private static SparkSession buildSparkSession(JobConfig config, int partitions) {
        String master = config.masterManagedBySubmit() ? "local[*]" : config.sparkMaster();
        SparkSession.Builder builder = SparkSession.builder()
                .appName("olist-real-data-import")
                .master(master == null || master.isEmpty() ? "local[*]" : master)
                .config("spark.ui.enabled", "false")
                .config("spark.sql.shuffle.partitions", String.valueOf(partitions));
        String hadoopHome = config.get("hadoop.home.dir");
        if (hadoopHome != null && !hadoopHome.trim().isEmpty()
                && System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            System.setProperty("hadoop.home.dir", hadoopHome.trim());
        }
        return builder.getOrCreate();
    }
}
