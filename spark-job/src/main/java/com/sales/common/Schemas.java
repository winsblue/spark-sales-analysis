package com.sales.common;

import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;

/**
 * 全局常量与表结构定义。
 *
 * <p>集中维护清洗规则、合法枚举值、以及各结果表的列顺序，
 * 避免多处硬编码导致写入错位。</p>
 */
public final class Schemas {

    private Schemas() {
    }

    // ==================================================================
    // 清洗规则编码（与 dwd_clean_reject.rule_code 对应）
    // ==================================================================

    public static final String R01_NULL_FILL = "R01";
    public static final String R02_DEDUP = "R02";
    public static final String R03_BAD_TIME = "R03";
    public static final String R04_BAD_QTY = "R04";
    public static final String R05_BAD_PRICE = "R05";
    public static final String R06_AMOUNT_FIX = "R06";
    public static final String R07_BAD_STATUS = "R07";
    public static final String R08_MISSING_DIM = "R08";

    // ==================================================================
    // 业务枚举
    // ==================================================================

    /** 合法订单状态枚举 */
    public static final String[] ORDER_STATUS_ALL = {"已完成", "已支付", "待付款", "已取消", "已退款"};

    /** 计入 GMV 的有效订单状态 */
    public static final String[] ORDER_STATUS_VALID = {"已完成", "已支付"};

    /** 退款状态 */
    public static final String REFUND_STATUS = "已退款";

    /** 数量合法上限 */
    public static final int MAX_QUANTITY = 999;

    /** 金额比对容差（元） */
    public static final double AMOUNT_TOLERANCE = 0.01d;

    /** 空值填充默认值 */
    public static final String UNKNOWN = "未知";
    public static final String UNKNOWN_CATEGORY = "未知类目";
    public static final String UNKNOWN_CATEGORY_ID = "UNKNOWN";
    public static final String UNKNOWN_PRODUCT = "未知商品";

    /**
     * 金额单位。
     *
     * <p>由 {@code JobConfig} 在启动时按配置项 {@code data.currency.unit} 注入
     * （默认"元"）。不同数据源的币种不同：模拟数据是人民币（元），
     * Olist 真实数据集是巴西雷亚尔（BRL），因此不能写死。</p>
     */
    public static String CURRENCY_UNIT = "元";

    // ==================================================================
    // 源数据结构（ODS 读取用，全部按字符串读入以保留脏数据）
    // ==================================================================

    /** 订单明细 CSV 的表头列（顺序必须与数据源一致） */
    public static final String[] ORDER_RAW_FIELDS = {
            "order_id", "order_time", "user_id", "product_id", "channel",
            "province", "city", "quantity", "unit_price", "discount_amount",
            "pay_amount", "order_status", "pay_type"
    };

    /** 商品维度 CSV 的表头列 */
    public static final String[] PRODUCT_RAW_FIELDS = {
            "product_id", "product_name", "category_id", "category_name", "brand", "cost_price", "launch_date"
    };

    public static StructType orderRawSchema() {
        StructType s = new StructType();
        for (String f : ORDER_RAW_FIELDS) {
            s = s.add(f, DataTypes.StringType, true);
        }
        return s;
    }

    public static StructType productRawSchema() {
        StructType s = new StructType();
        for (String f : PRODUCT_RAW_FIELDS) {
            s = s.add(f, DataTypes.StringType, true);
        }
        return s;
    }

    // ==================================================================
    // 各张表的写入列顺序（与 01_schema.sql 严格对应）
    // ==================================================================

    public static final String[] ODS_ORDER_COLUMNS = {
            "order_id", "order_time", "user_id", "product_id", "channel",
            "province", "city", "quantity", "unit_price", "discount_amount",
            "pay_amount", "order_status", "pay_type", "batch_id"
    };

    public static final String[] ODS_PRODUCT_COLUMNS = {
            "product_id", "product_name", "category_id", "category_name",
            "brand", "cost_price", "launch_date", "batch_id"
    };

    public static final String[] DWD_COLUMNS = {
            "order_id", "order_time", "stat_date", "stat_month", "stat_week",
            "user_id", "product_id", "product_name", "category_id", "category_name",
            "brand", "channel", "province", "city", "quantity", "unit_price",
            "discount_amount", "pay_amount", "order_status", "pay_type",
            "is_valid_order", "batch_id", "create_time"
    };

    public static final String[] OVERVIEW_COLUMNS = {
            "kpi_code", "kpi_name", "kpi_value", "kpi_unit", "kpi_desc",
            "stat_date", "batch_id", "update_time"
    };

    public static final String[] TREND_COLUMNS = {
            "stat_date", "order_cnt", "valid_order_cnt", "gmv", "sales_qty", "buyer_cnt",
            "avg_order_amount", "refund_order_cnt", "refund_rate", "update_time"
    };

    public static final String[] CATEGORY_COLUMNS = {
            "stat_date", "category_id", "category_name", "gmv", "sales_qty", "order_cnt", "buyer_cnt", "update_time"
    };

    public static final String[] PRODUCT_COLUMNS = {
            "stat_date", "product_id", "product_name", "category_name", "brand",
            "gmv", "sales_qty", "order_cnt", "update_time"
    };

    public static final String[] CHANNEL_COLUMNS = {
            "stat_date", "channel", "gmv", "order_cnt", "sales_qty", "buyer_cnt", "update_time"
    };

    public static final String[] PAYTYPE_COLUMNS = {
            "stat_date", "pay_type", "gmv", "order_cnt", "update_time"
    };

    public static final String[] REGION_COLUMNS = {
            "stat_date", "province", "gmv", "order_cnt", "buyer_cnt", "update_time"
    };

    public static final String[] CHANNEL_CATEGORY_COLUMNS = {
            "stat_date", "channel", "category_name", "gmv", "order_cnt", "update_time"
    };

    public static final String[] REJECT_COLUMNS = {
            "batch_id", "rule_code", "rule_desc", "order_id", "product_id", "raw_record", "create_time"
    };

    // ==================================================================
    // DWD 明细表结构（RDD 模式构造 DataFrame 时使用）
    // ==================================================================

    public static StructType dwdSchema() {
        return new StructType()
                .add("order_id", DataTypes.StringType, false)
                .add("order_time", DataTypes.TimestampType, false)
                .add("stat_date", DataTypes.DateType, false)
                .add("stat_month", DataTypes.StringType, false)
                .add("stat_week", DataTypes.StringType, true)
                .add("user_id", DataTypes.StringType, true)
                .add("product_id", DataTypes.StringType, false)
                .add("product_name", DataTypes.StringType, true)
                .add("category_id", DataTypes.StringType, false)
                .add("category_name", DataTypes.StringType, false)
                .add("brand", DataTypes.StringType, true)
                .add("channel", DataTypes.StringType, true)
                .add("province", DataTypes.StringType, false)
                .add("city", DataTypes.StringType, false)
                .add("quantity", DataTypes.IntegerType, false)
                .add("unit_price", DataTypes.createDecimalType(12, 2), false)
                .add("discount_amount", DataTypes.createDecimalType(12, 2), false)
                .add("pay_amount", DataTypes.createDecimalType(14, 2), false)
                .add("order_status", DataTypes.StringType, true)
                .add("pay_type", DataTypes.StringType, false)
                .add("is_valid_order", DataTypes.IntegerType, false)
                .add("batch_id", DataTypes.StringType, true)
                .add("create_time", DataTypes.TimestampType, true);
    }

    // ==================================================================
    // 全部需要写入的结果表（用于批量清空）
    // ==================================================================

    public static final String[] ALL_RESULT_TABLES = {
            "ods_order_detail", "ods_product", "dwd_order_detail", "dwd_clean_reject",
            "ads_overview", "ads_daily_trend", "ads_category_stat", "ads_product_stat",
            "ads_channel_stat", "ads_paytype_stat", "ads_region_stat",
            "ads_channel_category_stat", "ads_clean_stat", "etl_job_log"
    };
}
