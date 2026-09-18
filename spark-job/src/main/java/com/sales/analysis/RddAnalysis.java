package com.sales.analysis;

import com.sales.common.Schemas;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.broadcast.Broadcast;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SparkSession;
import scala.Tuple2;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 基于 RDD 的数据清洗与多维统计实现。
 *
 * <p>与 {@link DataFrameAnalysis} 计算完全相同的指标集合，
 * 用于在提高层中"比较不同处理方式的运行效果"（耗时、代码复杂度、可维护性）。</p>
 *
 * <p>实现要点：</p>
 * <ul>
 *   <li>使用 {@code textFile} 按行读取，自行完成字段切分与类型解析</li>
 *   <li>使用 {@code distinct()} 完成完全重复记录去重</li>
 *   <li>使用 {@code mapToPair + groupByKey} 分组，配合自定义累加器 {@link GroupStat} 计算指标</li>
 *   <li>维度关联通过 {@code Broadcast} 广播小表实现（Map 端关联，避免 Shuffle）</li>
 * </ul>
 */
public class RddAnalysis {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String[] TIME_PATTERNS = {
            "yyyy-MM-dd HH:mm:ss", "yyyy/MM/dd HH:mm", "yyyy-MM-dd'T'HH:mm:ss"
    };

    // ==================================================================
    // 中间记录对象
    // ==================================================================

    /** 单条订单明细记录（清洗前） */
    public static class OrderRecord implements Serializable {
        private static final long serialVersionUID = 1L;

        String raw;
        String orderId;
        Timestamp orderTime;
        String userId;
        String productId;
        String channel;
        String province;
        String city;
        int quantity;
        BigDecimal unitPrice = BigDecimal.ZERO;
        BigDecimal discount = BigDecimal.ZERO;
        BigDecimal payAmount = BigDecimal.ZERO;
        String orderStatus;
        String payType;

        // 校验标记
        boolean badTime;
        boolean badQty;
        boolean badPrice;
        boolean badAmount;
        boolean badStatus;
        boolean hasBlank;
        boolean missingDim;

        // 维度信息（关联商品主数据后填充）
        String productName;
        String categoryId;
        String categoryName;
        String brand;

        boolean discard() {
            return badTime || badQty || badPrice;
        }

        boolean isValidOrder() {
            return Schemas.ORDER_STATUS_VALID[0].equals(orderStatus)
                    || Schemas.ORDER_STATUS_VALID[1].equals(orderStatus);
        }

        String statDateKey() {
            return new SimpleDateFormat("yyyy-MM-dd").format(orderTime);
        }

        String statMonth() {
            return new SimpleDateFormat("yyyy-MM").format(orderTime);
        }

        String statWeek() {
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.setTime(orderTime);
            c.setFirstDayOfWeek(java.util.Calendar.MONDAY);
            c.setMinimalDaysInFirstWeek(4);
            return String.format("%04d-W%02d", c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.WEEK_OF_YEAR));
        }

        /** 转换为 DWD 表的行（列顺序与 Schemas.DWD_COLUMNS 一致） */
        Row toDwdRow(String batchId, Timestamp now) {
            return RowFactory.create(
                    orderId, orderTime, Date.valueOf(statDateKey()), statMonth(), statWeek(),
                    userId, productId, productName, categoryId, categoryName, brand,
                    channel, province, city, quantity,
                    unitPrice.setScale(2, RoundingMode.HALF_UP),
                    discount.setScale(2, RoundingMode.HALF_UP),
                    payAmount.setScale(2, RoundingMode.HALF_UP),
                    orderStatus, payType, isValidOrder() ? 1 : 0, batchId, now);
        }
    }

    /** 多指标累加器：支持 groupByKey 内的聚合，也支持 aggregate 全局聚合 */
    public static class GroupStat implements Serializable {
        private static final long serialVersionUID = 1L;

        final Set<String> orderIds = new HashSet<>();
        final Set<String> validOrderIds = new HashSet<>();
        final Set<String> userIds = new HashSet<>();
        final Set<String> refundOrderIds = new HashSet<>();
        BigDecimal gmv = BigDecimal.ZERO;
        BigDecimal grossAmount = BigDecimal.ZERO;
        BigDecimal discountAmount = BigDecimal.ZERO;
        long salesQty = 0;

        GroupStat add(OrderRecord r) {
            orderIds.add(r.orderId);
            if (r.badStatus) {
                // 状态非法的记录仍参与下单量统计，但不计入有效指标
                return this;
            }
            if (Schemas.REFUND_STATUS.equals(r.orderStatus)) {
                refundOrderIds.add(r.orderId);
            }
            if (r.isValidOrder()) {
                validOrderIds.add(r.orderId);
                userIds.add(r.userId);
                gmv = gmv.add(r.payAmount);
                salesQty += r.quantity;
                grossAmount = grossAmount.add(r.unitPrice.multiply(BigDecimal.valueOf(r.quantity)));
                discountAmount = discountAmount.add(r.discount);
            }
            return this;
        }

        GroupStat merge(GroupStat other) {
            orderIds.addAll(other.orderIds);
            validOrderIds.addAll(other.validOrderIds);
            userIds.addAll(other.userIds);
            refundOrderIds.addAll(other.refundOrderIds);
            gmv = gmv.add(other.gmv);
            grossAmount = grossAmount.add(other.grossAmount);
            discountAmount = discountAmount.add(other.discountAmount);
            salesQty += other.salesQty;
            return this;
        }

        long orderCnt() {
            return orderIds.size();
        }

        BigDecimal avgOrderAmount() {
            if (validOrderIds.isEmpty()) {
                return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            }
            return gmv.divide(BigDecimal.valueOf(validOrderIds.size()), 2, RoundingMode.HALF_UP);
        }

        BigDecimal refundRate() {
            if (orderIds.isEmpty()) {
                return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            }
            return BigDecimal.valueOf(refundOrderIds.size())
                    .divide(BigDecimal.valueOf(orderIds.size()), 4, RoundingMode.HALF_UP);
        }
    }

    /** 清洗统计结果 */
    public static class CleanStat {
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
    }

    /** RDD 计算的全部产出 */
    public static class RddResult {
        public CleanStat cleanStat = new CleanStat();
        public JavaRDD<Row> dwd;
        public JavaRDD<Row> rejects;
        public JavaRDD<Row> overview;
        public JavaRDD<Row> trend;
        public JavaRDD<Row> category;
        public JavaRDD<Row> product;
        public JavaRDD<Row> channel;
        public JavaRDD<Row> paytype;
        public JavaRDD<Row> region;
        public JavaRDD<Row> channelCategory;
    }

    private RddAnalysis() {
    }

    // ==================================================================
    // 主流程
    // ==================================================================

    public static RddResult run(SparkSession spark, String orderPath, String productPath, String batchId) {
        long start = System.currentTimeMillis();
        RddResult result = new RddResult();
        CleanStat stat = result.cleanStat;
        JavaSparkContext jsc = JavaSparkContext.fromSparkContext(spark.sparkContext());

        // ---------- 1. 读取并去重（R02） ----------
        JavaRDD<String> lines = jsc.textFile(orderPath)
                .filter(line -> line != null && !line.trim().isEmpty() && !line.startsWith("order_id"));
        stat.rawCnt = lines.count();

        JavaRDD<String> dedupedLines = lines.distinct();
        dedupedLines.cache();
        long dedupedCnt = dedupedLines.count();
        stat.dupRecordCnt = stat.rawCnt - dedupedCnt;

        // ---------- 2. 广播商品维度（Map 端关联） ----------
        Broadcast<Map<String, String[]>> dimBroadcast = jsc.broadcast(loadProductDim(spark, productPath));

        // ---------- 3. 解析 + 规则校验 ----------
        JavaRDD<OrderRecord> records = dedupedLines.map(line -> parse(line, dimBroadcast.value()));
        records.cache();

        stat.nullFieldCnt = records.filter(r -> r.hasBlank).count();
        stat.invalidTimeCnt = records.filter(r -> r.badTime).count();
        stat.invalidAmountCnt = records.filter(r -> r.badQty || r.badPrice || r.badAmount).count();
        stat.invalidStatusCnt = records.filter(r -> r.badStatus).count();
        // 维度缺失只统计"未被丢弃、真正进入 DWD 层"的记录，与 DataFrame 方式口径保持一致
        stat.missingDimCnt = records.filter(r -> !r.discard() && r.missingDim).count();
        stat.discardCnt = records.filter(OrderRecord::discard).count();
        stat.validCnt = dedupedCnt - stat.discardCnt;

        Timestamp now = new Timestamp(System.currentTimeMillis());

        // ---------- 4. 异常留痕表 ----------
        result.rejects = records.filter(OrderRecord::discard)
                .map(r -> RowFactory.create(
                        batchId,
                        r.badTime ? Schemas.R03_BAD_TIME : (r.badQty ? Schemas.R04_BAD_QTY : Schemas.R05_BAD_PRICE),
                        r.badTime ? "R03 下单时间无法解析，记录被丢弃"
                                : (r.badQty ? "R04 购买数量小于等于 0 或超过 999，记录被丢弃"
                                : "R05 商品单价小于等于 0，记录被丢弃"),
                        r.orderId, r.productId,
                        r.raw.length() > 500 ? r.raw.substring(0, 500) : r.raw,
                        now));

        // ---------- 5. 有效明细（DWD） ----------
        JavaRDD<OrderRecord> validRecords = records.filter(r -> !r.discard());
        validRecords.cache();
        result.dwd = validRecords.map(r -> r.toDwdRow(batchId, now));

        // ---------- 6. 多维统计 ----------
        // 6.1 每日趋势
        result.trend = validRecords
                .mapToPair(r -> new Tuple2<>(r.statDateKey(), r))
                .groupByKey()
                .map(kv -> {
                    GroupStat g = new GroupStat();
                    for (OrderRecord r : kv._2) {
                        g.add(r);
                    }
                    Date d = Date.valueOf(kv._1);
                    return RowFactory.create(
                            d, g.orderCnt(), (long) g.validOrderIds.size(), g.gmv.setScale(2, RoundingMode.HALF_UP),
                            g.salesQty, (long) g.userIds.size(), g.avgOrderAmount(),
                            (long) g.refundOrderIds.size(), g.refundRate(), now);
                })
                .sortBy(row -> String.valueOf(row.get(0)), true, 1);

        // 6.2 类目统计
        result.category = validRecords
                .mapToPair(r -> new Tuple2<>(r.statDateKey() + "|" + r.categoryId + "|" + r.categoryName, r))
                .groupByKey()
                .map(kv -> {
                    GroupStat g = new GroupStat();
                    for (OrderRecord r : kv._2) {
                        g.add(r);
                    }
                    String[] parts = kv._1.split("\\|", -1);
                    return RowFactory.create(
                            Date.valueOf(parts[0]), parts[1], parts[2],
                            g.gmv.setScale(2, RoundingMode.HALF_UP), g.salesQty,
                            (long) g.validOrderIds.size(), (long) g.userIds.size(), now);
                });

        // 6.3 商品统计
        result.product = validRecords
                .mapToPair(r -> new Tuple2<>(r.statDateKey() + "|" + r.productId, r))
                .groupByKey()
                .map(kv -> {
                    GroupStat g = new GroupStat();
                    OrderRecord sample = null;
                    for (OrderRecord r : kv._2) {
                        g.add(r);
                        if (sample == null) {
                            sample = r;
                        }
                    }
                    String[] parts = kv._1.split("\\|", -1);
                    return RowFactory.create(
                            Date.valueOf(parts[0]), parts[1], sample.productName, sample.categoryName, sample.brand,
                            g.gmv.setScale(2, RoundingMode.HALF_UP), g.salesQty, (long) g.validOrderIds.size(), now);
                });

        // 6.4 渠道统计
        result.channel = validRecords
                .mapToPair(r -> new Tuple2<>(r.statDateKey() + "|" + r.channel, r))
                .groupByKey()
                .map(kv -> {
                    GroupStat g = new GroupStat();
                    for (OrderRecord r : kv._2) {
                        g.add(r);
                    }
                    String[] parts = kv._1.split("\\|", -1);
                    return RowFactory.create(
                            Date.valueOf(parts[0]), parts[1], g.gmv.setScale(2, RoundingMode.HALF_UP),
                            g.orderCnt(), g.salesQty, (long) g.userIds.size(), now);
                });

        // 6.5 支付方式统计
        result.paytype = validRecords
                .mapToPair(r -> new Tuple2<>(r.statDateKey() + "|" + r.payType, r))
                .groupByKey()
                .map(kv -> {
                    GroupStat g = new GroupStat();
                    for (OrderRecord r : kv._2) {
                        g.add(r);
                    }
                    String[] parts = kv._1.split("\\|", -1);
                    return RowFactory.create(
                            Date.valueOf(parts[0]), parts[1],
                            g.gmv.setScale(2, RoundingMode.HALF_UP), g.orderCnt(), now);
                });

        // 6.6 省份统计
        result.region = validRecords
                .mapToPair(r -> new Tuple2<>(r.statDateKey() + "|" + r.province, r))
                .groupByKey()
                .map(kv -> {
                    GroupStat g = new GroupStat();
                    for (OrderRecord r : kv._2) {
                        g.add(r);
                    }
                    String[] parts = kv._1.split("\\|", -1);
                    return RowFactory.create(
                            Date.valueOf(parts[0]), parts[1], g.gmv.setScale(2, RoundingMode.HALF_UP),
                            g.orderCnt(), (long) g.userIds.size(), now);
                });

        // 6.7 渠道 × 类目交叉统计
        result.channelCategory = validRecords
                .mapToPair(r -> new Tuple2<>(r.statDateKey() + "|" + r.channel + "|" + r.categoryName, r))
                .groupByKey()
                .map(kv -> {
                    GroupStat g = new GroupStat();
                    for (OrderRecord r : kv._2) {
                        g.add(r);
                    }
                    String[] parts = kv._1.split("\\|", -1);
                    return RowFactory.create(
                            Date.valueOf(parts[0]), parts[1], parts[2],
                            g.gmv.setScale(2, RoundingMode.HALF_UP), g.orderCnt(), now);
                });

        // 6.8 核心指标总览
        result.overview = buildOverview(jsc, validRecords, batchId, now);

        stat.durationMs = System.currentTimeMillis() - start;
        records.unpersist();
        return result;
    }

    // ==================================================================
    // 辅助方法
    // ==================================================================

    /** 全周期核心指标 */
    private static JavaRDD<Row> buildOverview(JavaSparkContext jsc, JavaRDD<OrderRecord> validRecords,
                                              String batchId, Timestamp now) {
        GroupStat g = validRecords.aggregate(new GroupStat(), GroupStat::add, GroupStat::merge);
        Date statDate = validRecords
                .map(r -> Date.valueOf(r.statDateKey()))
                .max(java.util.Comparator.naturalOrder());

        BigDecimal gmv = g.gmv.setScale(2, RoundingMode.HALF_UP);
        BigDecimal avgItemPrice = g.salesQty == 0 ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : gmv.divide(BigDecimal.valueOf(g.salesQty), 2, RoundingMode.HALF_UP);
        BigDecimal discountRate = g.grossAmount.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
                : g.discountAmount.multiply(BigDecimal.valueOf(100))
                .divide(g.grossAmount, 4, RoundingMode.HALF_UP);

        List<Row> rows = new ArrayList<>();
        rows.add(kpi("GMV", "成交金额", gmv, Schemas.CURRENCY_UNIT, "有效订单（已完成/已支付）的实付金额之和", statDate, batchId, now));
        rows.add(kpi("ORDER_CNT", "下单量", BigDecimal.valueOf(g.orderCnt()), "单", "去重后的订单编号数量，含取消与退款订单", statDate, batchId, now));
        rows.add(kpi("VALID_ORDER_CNT", "有效订单量", BigDecimal.valueOf(g.validOrderIds.size()), "单", "订单状态为已完成或已支付的订单数量", statDate, batchId, now));
        rows.add(kpi("SALES_QTY", "销售件数", BigDecimal.valueOf(g.salesQty), "件", "有效订单中的商品购买数量之和", statDate, batchId, now));
        rows.add(kpi("BUYER_CNT", "下单用户数", BigDecimal.valueOf(g.userIds.size()), "人", "产生有效订单的去重用户数量", statDate, batchId, now));
        rows.add(kpi("AVG_ORDER_AMOUNT", "客单价", g.avgOrderAmount(), Schemas.CURRENCY_UNIT, "GMV ÷ 有效订单量", statDate, batchId, now));
        rows.add(kpi("AVG_ITEM_PRICE", "件单价", avgItemPrice, Schemas.CURRENCY_UNIT, "GMV ÷ 销售件数", statDate, batchId, now));
        rows.add(kpi("REFUND_RATE", "退款率",
                BigDecimal.valueOf(g.refundOrderIds.size()).multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(Math.max(g.orderCnt(), 1)), 4, RoundingMode.HALF_UP),
                "%", "退款订单量 ÷ 下单量", statDate, batchId, now));
        rows.add(kpi("DISCOUNT_RATE", "折扣率", discountRate, "%", "优惠金额 ÷ 原价金额", statDate, batchId, now));
        return jsc.parallelize(rows, 1);
    }

    private static Row kpi(String code, String name, BigDecimal value, String unit, String desc,
                           Date statDate, String batchId, Timestamp now) {
        return RowFactory.create(code, name, value.setScale(4, RoundingMode.HALF_UP), unit, desc, statDate, batchId, now);
    }

    /** 解析一行 CSV 记录并完成规则校验 */
    static OrderRecord parse(String line, Map<String, String[]> dim) {
        String[] f = line.split(",", -1);
        OrderRecord r = new OrderRecord();
        r.raw = line;
        if (f.length < 13) {
            r.badTime = true;
            return r;
        }
        r.orderId = blankToNull(f[0]);
        r.userId = blankToNull(f[2]);
        r.productId = blankToNull(f[3]);
        r.channel = blankToNull(f[4]);
        r.province = blankToNull(f[5]);
        r.city = blankToNull(f[6]);
        r.orderStatus = blankToNull(f[11]);
        r.payType = blankToNull(f[12]);

        // R01 空值检测
        r.hasBlank = r.province == null || r.city == null || r.payType == null;

        // R03 时间解析
        r.orderTime = parseTime(blankToNull(f[1]));
        r.badTime = r.orderTime == null;

        // R04 数量校验
        Integer qty = parseInt(f[7]);
        r.badQty = qty == null || qty <= 0 || qty > Schemas.MAX_QUANTITY;
        r.quantity = qty == null ? 0 : qty;

        // R05 单价校验
        BigDecimal price = parseDecimal(f[8]);
        r.badPrice = price == null || price.compareTo(BigDecimal.ZERO) <= 0;
        r.unitPrice = price == null ? BigDecimal.ZERO : price;

        BigDecimal discount = parseDecimal(f[9]);
        r.discount = discount == null ? BigDecimal.ZERO : discount;
        BigDecimal pay = parseDecimal(f[10]);
        r.payAmount = pay == null ? BigDecimal.ZERO : pay;

        // R06 金额一致性校验
        if (!r.badQty && !r.badPrice) {
            BigDecimal expected = r.unitPrice.multiply(BigDecimal.valueOf(r.quantity))
                    .subtract(r.discount).setScale(2, RoundingMode.HALF_UP);
            r.badAmount = pay == null
                    || pay.subtract(expected).abs().compareTo(new BigDecimal("0.01")) > 0;
            // 按公式重算，保证下游口径一致
            r.payAmount = expected;
        }

        // R07 状态标准化
        r.badStatus = r.orderStatus == null || !Arrays.asList(Schemas.ORDER_STATUS_ALL).contains(r.orderStatus);
        if (r.badStatus) {
            r.orderStatus = "其他";
        }

        // R01 空值填充
        if (r.province == null) {
            r.province = Schemas.UNKNOWN;
        }
        if (r.city == null) {
            r.city = Schemas.UNKNOWN;
        }
        if (r.payType == null) {
            r.payType = Schemas.UNKNOWN;
        }

        // R08 维度关联
        String[] d = r.productId == null ? null : dim.get(r.productId);
        if (d == null) {
            r.missingDim = true;
            r.productName = Schemas.UNKNOWN_PRODUCT;
            r.categoryId = Schemas.UNKNOWN_CATEGORY_ID;
            r.categoryName = Schemas.UNKNOWN_CATEGORY;
            r.brand = Schemas.UNKNOWN;
        } else {
            r.productName = d[0];
            r.categoryId = d[1];
            r.categoryName = d[2];
            r.brand = d[3];
        }
        return r;
    }

    /** 读取商品维度并转成 Map，供广播使用 */
    private static Map<String, String[]> loadProductDim(SparkSession spark, String productPath) {
        Dataset<Row> dim = spark.read()
                .option("header", "true")
                .option("encoding", "UTF-8")
                .schema(Schemas.productRawSchema())
                .csv(productPath);
        Map<String, String[]> map = new HashMap<>();
        for (Row row : dim.collectAsList()) {
            String pid = blankToNull(row.isNullAt(0) ? null : row.getString(0));
            if (pid == null || map.containsKey(pid)) {
                continue;
            }
            map.put(pid, new String[]{
                    valueOr(row, 1, Schemas.UNKNOWN_PRODUCT),
                    valueOr(row, 2, Schemas.UNKNOWN_CATEGORY_ID),
                    valueOr(row, 3, Schemas.UNKNOWN_CATEGORY),
                    valueOr(row, 4, Schemas.UNKNOWN)
            });
        }
        return map;
    }

    private static String valueOr(Row row, int idx, String def) {
        if (row.isNullAt(idx)) {
            return def;
        }
        String v = row.getString(idx);
        return (v == null || v.trim().isEmpty()) ? def : v.trim();
    }

    private static String blankToNull(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    private static Integer parseInt(String v) {
        String t = blankToNull(v);
        if (t == null) {
            return null;
        }
        try {
            return Integer.valueOf(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal parseDecimal(String v) {
        String t = blankToNull(v);
        if (t == null) {
            return null;
        }
        try {
            return new BigDecimal(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Timestamp parseTime(String v) {
        if (v == null) {
            return null;
        }
        for (String pattern : TIME_PATTERNS) {
            try {
                SimpleDateFormat fmt = new SimpleDateFormat(pattern);
                fmt.setLenient(false);
                java.util.Date d = fmt.parse(v);
                if (d != null) {
                    return new Timestamp(d.getTime());
                }
            } catch (Exception ignored) {
                // 尝试下一种格式
            }
        }
        return null;
    }

    /** 供外部单元校验使用 */
    public static LocalDate toLocalDate(String dateKey) {
        return LocalDate.parse(dateKey, DATE_FMT);
    }

    /** 便于调试：打印 RDD 结果前若干行 */
    public static void printPreview(String title, JavaRDD<Row> rdd, int limit) {
        System.out.println("---------- " + title + " ----------");
        Iterator<Row> it = rdd.take(limit).iterator();
        while (it.hasNext()) {
            System.out.println("  " + it.next());
        }
    }
}
