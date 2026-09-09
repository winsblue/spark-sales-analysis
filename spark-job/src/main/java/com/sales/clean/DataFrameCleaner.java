package com.sales.clean;

import com.sales.common.Cols;
import com.sales.common.Schemas;
import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.expressions.Window;
import org.apache.spark.sql.expressions.WindowSpec;
import org.apache.spark.sql.functions;
import org.apache.spark.sql.types.DataTypes;

import java.math.BigDecimal;

import static org.apache.spark.sql.functions.abs;
import static org.apache.spark.sql.functions.broadcast;
import static org.apache.spark.sql.functions.col;
import static org.apache.spark.sql.functions.concat_ws;
import static org.apache.spark.sql.functions.current_timestamp;
import static org.apache.spark.sql.functions.date_format;
import static org.apache.spark.sql.functions.lit;
import static org.apache.spark.sql.functions.lpad;
import static org.apache.spark.sql.functions.not;
import static org.apache.spark.sql.functions.nullif;
import static org.apache.spark.sql.functions.row_number;
import static org.apache.spark.sql.functions.substring;
import static org.apache.spark.sql.functions.to_date;
import static org.apache.spark.sql.functions.to_timestamp;
import static org.apache.spark.sql.functions.trim;
import static org.apache.spark.sql.functions.weekofyear;
import static org.apache.spark.sql.functions.when;
import static org.apache.spark.sql.functions.year;

/**
 * 基于 DataFrame / SparkSQL 的数据清洗实现。
 *
 * <p>清洗规则（详见 docs/04-数据处理流程.md）：</p>
 * <ul>
 *   <li>R01 空值填充：province / city / pay_type 为空则填充为"未知"</li>
 *   <li>R02 重复记录：完全相同的记录（13 个业务字段全一致）仅保留第一条</li>
 *   <li>R03 时间非法：order_time 无法按任一约定格式解析 → 丢弃</li>
 *   <li>R04 数量非法：quantity ≤ 0 或 &gt; 999 → 丢弃</li>
 *   <li>R05 单价非法：unit_price ≤ 0 → 丢弃</li>
 *   <li>R06 金额不一致：|pay_amount - (quantity×unit_price - discount)| &gt; 0.01 → 按公式重算</li>
 *   <li>R07 状态非法：order_status 不在合法枚举内 → 标准化为"其他"</li>
 *   <li>R08 维度缺失：商品主数据中不存在该 product_id → 类目填充为"未知类目"</li>
 * </ul>
 *
 * <p>统计口径说明：invalidTimeCnt / invalidAmountCnt 等计数器统计的是"规则命中次数"，
 * 同一行可能同时命中多条规则，因此各计数器之和可能大于 discardCnt。</p>
 */
public class DataFrameCleaner {

    /** 清洗结果载体 */
    public static class CleanResult {
        /** 清洗后的明细数据（DWD） */
        public Dataset<Row> dwd;
        /** 被丢弃的记录（含违反的规则编码） */
        public Dataset<Row> rejects;
        public long rawCnt;
        public long nullFieldCnt;
        public long dupRecordCnt;
        public long invalidTimeCnt;
        public long invalidAmountCnt;
        public long invalidStatusCnt;
        public long missingDimCnt;
        public long validCnt;
        public long discardCnt;
        public long durationMs;

        public double qualityScore() {
            return rawCnt == 0 ? 0d : (double) validCnt / (double) rawCnt;
        }

        public void show() {
            System.out.println("---------------- 数据清洗统计 ----------------");
            System.out.println("原始记录数        : " + rawCnt);
            System.out.println("R01 含空值记录    : " + nullFieldCnt);
            System.out.println("R02 重复记录      : " + dupRecordCnt);
            System.out.println("R03 时间异常      : " + invalidTimeCnt);
            System.out.println("R04-R06 金额异常  : " + invalidAmountCnt);
            System.out.println("R07 状态非法      : " + invalidStatusCnt);
            System.out.println("R08 维度缺失      : " + missingDimCnt);
            System.out.println("丢弃记录数        : " + discardCnt);
            System.out.println("有效记录数        : " + validCnt);
            System.out.printf("数据质量得分      : %.4f%n", qualityScore());
            System.out.println("清洗耗时(ms)      : " + durationMs);
            System.out.println("---------------------------------------------");
        }
    }

    private DataFrameCleaner() {
    }

    public static CleanResult clean(SparkSession spark, String orderPath, String productPath, String batchId) {
        long start = System.currentTimeMillis();
        CleanResult result = new CleanResult();

        // ---------- 1. 读取原始数据（全部按字符串读入，保留脏数据原貌） ----------
        Dataset<Row> rawOrders = spark.read()
                .option("header", "true")
                .option("encoding", "UTF-8")
                .option("mode", "PERMISSIVE")
                .schema(Schemas.orderRawSchema())
                .csv(orderPath);

        Dataset<Row> rawProducts = spark.read()
                .option("header", "true")
                .option("encoding", "UTF-8")
                .option("mode", "PERMISSIVE")
                .schema(Schemas.productRawSchema())
                .csv(productPath);

        result.rawCnt = rawOrders.count();

        // ---------- 2. R02 完全重复记录去重 ----------
        // 以 13 个业务字段作为去重键；用自增行号保证去重后的记录顺序稳定
        Column[] keyCols = new Column[Schemas.ORDER_RAW_FIELDS.length + 1];
        for (int i = 0; i < Schemas.ORDER_RAW_FIELDS.length; i++) {
            keyCols[i] = col(Schemas.ORDER_RAW_FIELDS[i]);
        }
        Dataset<Row> rawWithId = rawOrders.withColumn("rid", functions.monotonically_increasing_id());
        keyCols[Schemas.ORDER_RAW_FIELDS.length] = col("rid");
        WindowSpec dedupWindow = Window.partitionBy(
                java.util.Arrays.copyOf(keyCols, Schemas.ORDER_RAW_FIELDS.length)).orderBy(col("rid"));
        Dataset<Row> deduped = rawWithId
                .withColumn("rn", row_number().over(dedupWindow))
                .filter(col("rn").equalTo(1))
                .drop("rn", "rid")
                .persist();
        long dedupedCnt = deduped.count();
        result.dupRecordCnt = result.rawCnt - dedupedCnt;

        // ---------- 3. 构造校验用表达式 ----------
        // R03 时间解析：支持三种常见格式，全部解析失败则视为脏数据
        Column ts = org.apache.spark.sql.functions.coalesce(
                to_timestamp(col("order_time"), "yyyy-MM-dd HH:mm:ss"),
                to_timestamp(col("order_time"), "yyyy/MM/dd HH:mm"),
                to_timestamp(col("order_time"), "yyyy-MM-dd'T'HH:mm:ss"));
        Column badTime = ts.isNull();

        // R04 数量校验
        Column qty = col("quantity").cast(DataTypes.IntegerType);
        Column badQty = qty.isNull().or(qty.leq(0)).or(qty.geq(Schemas.MAX_QUANTITY + 1));

        // R05 单价校验
        Column price = col("unit_price").cast(DataTypes.createDecimalType(12, 2));
        Column badPrice = price.isNull().or(price.leq(0));

        // R06 金额校验：实付金额 = 数量 × 单价 - 优惠金额
        Column discount = org.apache.spark.sql.functions.coalesce(
                col("discount_amount").cast(DataTypes.createDecimalType(12, 2)),
                lit(BigDecimal.ZERO));
        Column computedPay = org.apache.spark.sql.functions.round(
                qty.cast(DataTypes.createDecimalType(20, 4)).multiply(price)
                        .minus(discount.cast(DataTypes.createDecimalType(20, 4))), 2)
                .cast(DataTypes.createDecimalType(14, 2));
        Column payAmount = col("pay_amount").cast(DataTypes.createDecimalType(14, 2));
        Column badAmount = badQty.or(badPrice)
                .or(payAmount.isNull())
                .or(abs(payAmount.minus(computedPay)).gt(lit(new BigDecimal("0.01"))));

        // R07 状态合法性
        Column badStatus = when(col("order_status").isNull(), lit(true))
                .otherwise(not(col("order_status").isin((Object[]) Schemas.ORDER_STATUS_ALL)));

        // R01 空值检测（用于统计，不做丢弃）
        Column hasBlank = col("province").isNull()
                .or(trim(col("province")).equalTo(lit("")))
                .or(col("city").isNull())
                .or(trim(col("city")).equalTo(lit("")))
                .or(col("pay_type").isNull())
                .or(trim(col("pay_type")).equalTo(lit("")));

        // ---------- 4. 统计各规则命中数量 ----------
        result.nullFieldCnt = deduped.filter(hasBlank).count();
        result.invalidTimeCnt = deduped.filter(badTime).count();
        result.invalidAmountCnt = deduped.filter(badAmount).count();
        result.invalidStatusCnt = deduped.filter(badStatus).count();

        Column discardCondition = badTime.or(badQty).or(badPrice);
        result.discardCnt = deduped.filter(discardCondition).count();
        result.validCnt = dedupedCnt - result.discardCnt;

        // ---------- 5. 输出被丢弃的记录（留痕，便于追溯） ----------
        result.rejects = deduped
                .filter(discardCondition)
                .withColumn("batch_id", lit(batchId))
                .withColumn("rule_code",
                        when(badTime, lit(Schemas.R03_BAD_TIME))
                                .when(badQty, lit(Schemas.R04_BAD_QTY))
                                .otherwise(lit(Schemas.R05_BAD_PRICE)))
                .withColumn("rule_desc",
                        when(badTime, lit("R03 下单时间无法解析，记录被丢弃"))
                                .when(badQty, lit("R04 购买数量小于等于 0 或超过 999，记录被丢弃"))
                                .otherwise(lit("R05 商品单价小于等于 0，记录被丢弃")))
                .withColumn("raw_record", substring(concat_ws("|",
                        col("order_id"), col("order_time"), col("quantity"),
                        col("unit_price"), col("pay_amount")), 1, 500))
                .withColumn("create_time", current_timestamp())
                .select(Schemas.REJECT_COLUMNS[0], Schemas.REJECT_COLUMNS[1], Schemas.REJECT_COLUMNS[2],
                        Schemas.REJECT_COLUMNS[3], Schemas.REJECT_COLUMNS[4],
                        Schemas.REJECT_COLUMNS[5], Schemas.REJECT_COLUMNS[6]);

        // ---------- 6. 构造 DWD 明细（R01/R06/R07 在此处生效） ----------
        Dataset<Row> valid = deduped.filter(not(discardCondition));

        Dataset<Row> dwdBase = valid
                .withColumn("order_time", ts)
                .withColumn("stat_date", to_date(ts))
                .withColumn("stat_month", date_format(ts, "yyyy-MM"))
                .withColumn("stat_week", org.apache.spark.sql.functions.concat(
                        year(ts), lit("-W"), lpad(weekofyear(ts).cast(DataTypes.StringType), 2, "0")))
                .withColumn("quantity", qty)
                .withColumn("unit_price", price)
                .withColumn("discount_amount", discount.cast(DataTypes.createDecimalType(12, 2)))
                // R06：金额不一致的按公式重算，保证下游指标口径正确
                .withColumn("pay_amount", computedPay)
                // R01：空值填充为"未知"
                .withColumn("province", org.apache.spark.sql.functions.coalesce(
                        nullif(trim(col("province")), lit("")), lit(Schemas.UNKNOWN)))
                .withColumn("city", org.apache.spark.sql.functions.coalesce(
                        nullif(trim(col("city")), lit("")), lit(Schemas.UNKNOWN)))
                .withColumn("pay_type", org.apache.spark.sql.functions.coalesce(
                        nullif(trim(col("pay_type")), lit("")), lit(Schemas.UNKNOWN)))
                // R07：非法状态统一标准化为"其他"
                .withColumn("order_status",
                        when(col("order_status").isin((Object[]) Schemas.ORDER_STATUS_ALL), col("order_status"))
                                .otherwise(lit("其他")))
                .withColumn("is_valid_order",
                        when(col("order_status").isin((Object[]) Schemas.ORDER_STATUS_VALID), lit(1)).otherwise(lit(0)));

        // ---------- 7. 关联商品维度（R08） ----------
        Dataset<Row> productDim = rawProducts
                .select(col("product_id").as("dim_product_id"),
                        nullif(trim(col("product_name")), lit("")).as("dim_product_name"),
                        nullif(trim(col("category_id")), lit("")).as("dim_category_id"),
                        nullif(trim(col("category_name")), lit("")).as("dim_category_name"),
                        nullif(trim(col("brand")), lit("")).as("dim_brand"))
                .dropDuplicates("dim_product_id");

        Dataset<Row> joined = dwdBase.join(broadcast(productDim),
                dwdBase.col("product_id").equalTo(productDim.col("dim_product_id")), "left");

        result.missingDimCnt = joined.filter(col("dim_product_id").isNull()).count();

        result.dwd = joined
                .withColumn("product_name",
                        org.apache.spark.sql.functions.coalesce(col("dim_product_name"), lit(Schemas.UNKNOWN_PRODUCT)))
                .withColumn("category_id",
                        org.apache.spark.sql.functions.coalesce(col("dim_category_id"), lit(Schemas.UNKNOWN_CATEGORY_ID)))
                .withColumn("category_name",
                        org.apache.spark.sql.functions.coalesce(col("dim_category_name"), lit(Schemas.UNKNOWN_CATEGORY)))
                .withColumn("brand",
                        org.apache.spark.sql.functions.coalesce(col("dim_brand"), lit(Schemas.UNKNOWN)))
                .withColumn("batch_id", lit(batchId))
                .withColumn("create_time", current_timestamp())
                .select(Cols.of(Schemas.DWD_COLUMNS));

        // 触发一次计算，保证 DWD 结果在此阶段完成物化（便于统计整体清洗耗时）
        result.dwd.persist();
        result.validCnt = result.dwd.count();
        result.rejects.persist();
        result.durationMs = System.currentTimeMillis() - start;

        return result;
    }
}
