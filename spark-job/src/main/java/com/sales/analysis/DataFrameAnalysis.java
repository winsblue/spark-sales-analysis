package com.sales.analysis;

import com.sales.common.Cols;
import com.sales.common.Schemas;
import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import static org.apache.spark.sql.functions.col;
import static org.apache.spark.sql.functions.countDistinct;
import static org.apache.spark.sql.functions.current_timestamp;
import static org.apache.spark.sql.functions.lit;
import static org.apache.spark.sql.functions.max;
import static org.apache.spark.sql.functions.round;
import static org.apache.spark.sql.functions.sum;
import static org.apache.spark.sql.functions.when;

/**
 * 基于 DataFrame / SparkSQL 的多维指标统计实现。
 *
 * <p>指标口径（与 docs/03-概念模型与逻辑模型设计.md 中的指标定义表一致）：</p>
 * <ul>
 *   <li>有效订单：order_status 属于"已完成/已支付"的订单</li>
 *   <li>GMV：有效订单的实付金额之和</li>
 *   <li>下单量：去重后的订单编号数量（含取消/退款订单）</li>
 *   <li>客单价：GMV ÷ 有效订单量</li>
 *   <li>退款率：退款订单量 ÷ 下单量</li>
 *   <li>折扣率：优惠金额 ÷ 原价金额（数量×单价）</li>
 * </ul>
 *
 * <p>所有结果表按"统计日期 + 维度"存全量聚合值，
 * 排名（TopN）与占比由后端在查询时计算，以便前端筛选条件能够联动。</p>
 */
public class DataFrameAnalysis {

    /** 分析结果载体：每张 ADS 表对应一个 Dataset */
    public static class AnalysisResult {
        public Dataset<Row> overview;
        public Dataset<Row> trend;
        public Dataset<Row> category;
        public Dataset<Row> product;
        public Dataset<Row> channel;
        public Dataset<Row> paytype;
        public Dataset<Row> region;
        public Dataset<Row> channelCategory;
    }

    private DataFrameAnalysis() {
    }

    public static AnalysisResult analyze(SparkSession spark, Dataset<Row> dwd, String batchId) {
        AnalysisResult r = new AnalysisResult();

        // 有效订单判定与条件聚合表达式
        Column isValid = col("is_valid_order").equalTo(lit(1));
        Column validOrderId = when(isValid, col("order_id"));
        Column validUserId = when(isValid, col("user_id"));
        Column validPay = when(isValid, col("pay_amount")).otherwise(lit(BigDecimal.ZERO));
        Column validQty = when(isValid, col("quantity")).otherwise(lit(0));
        Column refundOrderId = when(col("order_status").equalTo(lit(Schemas.REFUND_STATUS)), col("order_id"));

        // ---------- 1. 每日销售趋势 ----------
        r.trend = dwd.groupBy("stat_date").agg(
                        countDistinct(col("order_id")).as("order_cnt"),
                        countDistinct(validOrderId).as("valid_order_cnt"),
                        sum(validPay).as("gmv_raw"),
                        sum(validQty).as("sales_qty"),
                        countDistinct(validUserId).as("buyer_cnt"),
                        countDistinct(refundOrderId).as("refund_order_cnt"))
                .withColumn("gmv", col("gmv_raw").cast(DataTypes.createDecimalType(20, 2)))
                .withColumn("avg_order_amount",
                        when(col("valid_order_cnt").gt(0),
                                round(col("gmv_raw").cast(DataTypes.DoubleType)
                                        .divide(col("valid_order_cnt").cast(DataTypes.DoubleType)), 2))
                                .otherwise(lit(new BigDecimal("0.00")))
                                .cast(DataTypes.createDecimalType(14, 2)))
                .withColumn("refund_rate",
                        when(col("order_cnt").gt(0),
                                round(col("refund_order_cnt").cast(DataTypes.DoubleType)
                                        .divide(col("order_cnt").cast(DataTypes.DoubleType)), 4))
                                .otherwise(lit(BigDecimal.ZERO))
                                .cast(DataTypes.createDecimalType(10, 4)))
                .withColumn("update_time", current_timestamp())
                .select(Cols.of(Schemas.TREND_COLUMNS));

        // ---------- 2. 类目销售统计 ----------
        r.category = dwd.groupBy("stat_date", "category_id", "category_name").agg(
                        sum(validPay).as("gmv_raw"),
                        sum(validQty).as("sales_qty"),
                        countDistinct(validOrderId).as("order_cnt"),
                        countDistinct(validUserId).as("buyer_cnt"))
                .withColumn("gmv", col("gmv_raw").cast(DataTypes.createDecimalType(20, 2)))
                .withColumn("update_time", current_timestamp())
                .select(Cols.of(Schemas.CATEGORY_COLUMNS));

        // ---------- 3. 商品销售统计 ----------
        r.product = dwd.groupBy("stat_date", "product_id", "product_name", "category_name", "brand").agg(
                        sum(validPay).as("gmv_raw"),
                        sum(validQty).as("sales_qty"),
                        countDistinct(validOrderId).as("order_cnt"))
                .withColumn("gmv", col("gmv_raw").cast(DataTypes.createDecimalType(20, 2)))
                .withColumn("update_time", current_timestamp())
                .select(Cols.of(Schemas.PRODUCT_COLUMNS));

        // ---------- 4. 渠道销售统计 ----------
        r.channel = dwd.groupBy("stat_date", "channel").agg(
                        sum(validPay).as("gmv_raw"),
                        countDistinct(validOrderId).as("order_cnt"),
                        sum(validQty).as("sales_qty"),
                        countDistinct(validUserId).as("buyer_cnt"))
                .withColumn("gmv", col("gmv_raw").cast(DataTypes.createDecimalType(20, 2)))
                .withColumn("update_time", current_timestamp())
                .select(Cols.of(Schemas.CHANNEL_COLUMNS));

        // ---------- 5. 支付方式统计 ----------
        r.paytype = dwd.groupBy("stat_date", "pay_type").agg(
                        sum(validPay).as("gmv_raw"),
                        countDistinct(validOrderId).as("order_cnt"))
                .withColumn("gmv", col("gmv_raw").cast(DataTypes.createDecimalType(20, 2)))
                .withColumn("update_time", current_timestamp())
                .select(Cols.of(Schemas.PAYTYPE_COLUMNS));

        // ---------- 6. 省份销售统计 ----------
        r.region = dwd.groupBy("stat_date", "province").agg(
                        sum(validPay).as("gmv_raw"),
                        countDistinct(validOrderId).as("order_cnt"),
                        countDistinct(validUserId).as("buyer_cnt"))
                .withColumn("gmv", col("gmv_raw").cast(DataTypes.createDecimalType(20, 2)))
                .withColumn("update_time", current_timestamp())
                .select(Cols.of(Schemas.REGION_COLUMNS));

        // ---------- 7. 渠道 × 类目 交叉统计（分组对比） ----------
        r.channelCategory = dwd.groupBy("stat_date", "channel", "category_name").agg(
                        sum(validPay).as("gmv_raw"),
                        countDistinct(validOrderId).as("order_cnt"))
                .withColumn("gmv", col("gmv_raw").cast(DataTypes.createDecimalType(20, 2)))
                .withColumn("update_time", current_timestamp())
                .select(Cols.of(Schemas.CHANNEL_CATEGORY_COLUMNS));

        // ---------- 8. 核心指标总览 ----------
        r.overview = buildOverview(spark, dwd, validPay, validQty, validOrderId, validUserId, refundOrderId, batchId);

        return r;
    }

    /** 计算全周期核心指标，输出到 ads_overview */
    private static Dataset<Row> buildOverview(SparkSession spark, Dataset<Row> dwd,
                                              Column validPay, Column validQty, Column validOrderId,
                                              Column validUserId, Column refundOrderId, String batchId) {
        Column isValid = col("is_valid_order").equalTo(lit(1));
        Column grossAmount = when(isValid,
                col("quantity").cast(DataTypes.createDecimalType(20, 4)).multiply(col("unit_price")))
                .otherwise(lit(BigDecimal.ZERO));
        Column discountAmount = when(isValid, col("discount_amount")).otherwise(lit(BigDecimal.ZERO));

        Row agg = dwd.agg(
                        sum(validPay).as("gmv"),
                        sum(validQty).as("sales_qty"),
                        sum(grossAmount).as("gross_amount"),
                        sum(discountAmount).as("discount_amount"),
                        countDistinct(validOrderId).as("valid_order_cnt"),
                        countDistinct(col("order_id")).as("order_cnt"),
                        countDistinct(validUserId).as("buyer_cnt"),
                        countDistinct(refundOrderId).as("refund_order_cnt"))
                .first();

        BigDecimal gmv = decimal(agg.get(0));
        long salesQty = agg.getLong(1);
        BigDecimal gross = decimal(agg.get(2));
        BigDecimal discount = decimal(agg.get(3));
        long validOrderCnt = agg.getLong(4);
        long orderCnt = agg.getLong(5);
        long buyerCnt = agg.getLong(6);
        long refundOrderCnt = agg.getLong(7);

        Date statDate = dwd.select(max(col("stat_date"))).first().getDate(0);
        Timestamp now = new Timestamp(System.currentTimeMillis());

        List<Row> kpis = new ArrayList<>();
        kpis.add(kpi("GMV", "成交金额", gmv, "元", "有效订单（已完成/已支付）的实付金额之和", statDate, batchId, now));
        kpis.add(kpi("ORDER_CNT", "下单量", BigDecimal.valueOf(orderCnt), "单", "去重后的订单编号数量，含取消与退款订单", statDate, batchId, now));
        kpis.add(kpi("VALID_ORDER_CNT", "有效订单量", BigDecimal.valueOf(validOrderCnt), "单", "订单状态为已完成或已支付的订单数量", statDate, batchId, now));
        kpis.add(kpi("SALES_QTY", "销售件数", BigDecimal.valueOf(salesQty), "件", "有效订单中的商品购买数量之和", statDate, batchId, now));
        kpis.add(kpi("BUYER_CNT", "下单用户数", BigDecimal.valueOf(buyerCnt), "人", "产生有效订单的去重用户数量", statDate, batchId, now));
        kpis.add(kpi("AVG_ORDER_AMOUNT", "客单价",
                divide(gmv, BigDecimal.valueOf(Math.max(validOrderCnt, 1)), 2), "元", "GMV ÷ 有效订单量", statDate, batchId, now));
        kpis.add(kpi("AVG_ITEM_PRICE", "件单价",
                divide(gmv, BigDecimal.valueOf(Math.max(salesQty, 1)), 2), "元", "GMV ÷ 销售件数", statDate, batchId, now));
        kpis.add(kpi("REFUND_RATE", "退款率",
                divide(BigDecimal.valueOf(refundOrderCnt).multiply(BigDecimal.valueOf(100)),
                        BigDecimal.valueOf(Math.max(orderCnt, 1)), 4), "%", "退款订单量 ÷ 下单量", statDate, batchId, now));
        kpis.add(kpi("DISCOUNT_RATE", "折扣率",
                divide(discount.multiply(BigDecimal.valueOf(100)),
                        gross.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ONE : gross, 4), "%",
                "优惠金额 ÷ 原价金额", statDate, batchId, now));

        StructType schema = new StructType()
                .add("kpi_code", DataTypes.StringType, false)
                .add("kpi_name", DataTypes.StringType, false)
                .add("kpi_value", DataTypes.createDecimalType(20, 4), false)
                .add("kpi_unit", DataTypes.StringType, true)
                .add("kpi_desc", DataTypes.StringType, true)
                .add("stat_date", DataTypes.DateType, false)
                .add("batch_id", DataTypes.StringType, true)
                .add("update_time", DataTypes.TimestampType, true);

        return spark.createDataFrame(kpis, schema);
    }

    private static Row kpi(String code, String name, BigDecimal value, String unit, String desc,
                           Date statDate, String batchId, Timestamp now) {
        return RowFactory.create(code, name, value.setScale(4, RoundingMode.HALF_UP), unit, desc, statDate, batchId, now);
    }

    private static BigDecimal decimal(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        if (v instanceof BigDecimal) {
            return (BigDecimal) v;
        }
        return new BigDecimal(v.toString());
    }

    private static BigDecimal divide(BigDecimal a, BigDecimal b, int scale) {
        if (b == null || b.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_UP);
        }
        return a.divide(b, scale, RoundingMode.HALF_UP);
    }
}
