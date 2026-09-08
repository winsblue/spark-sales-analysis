package com.sales.generator;

import com.sales.config.JobConfig;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 模拟电商销售数据生成器。
 *
 * <p>生成两张 CSV 数据源文件：</p>
 * <ul>
 *   <li>{@code ods_order_detail.csv} —— 订单明细（事实数据，约 20 万行）</li>
 *   <li>{@code ods_product.csv}      —— 商品维度（约 200 条）</li>
 * </ul>
 *
 * <p>为支撑后续"数据清洗"环节，生成器会按固定比例注入以下脏数据：</p>
 * <table border="1">
 *   <tr><th>脏数据类型</th><th>注入比例</th><th>处理规则</th></tr>
 *   <tr><td>省份/城市/支付方式空值</td><td>约 1%</td><td>R01 空值填充为"未知"</td></tr>
 *   <tr><td>重复记录（订单号+商品号重复）</td><td>约 1.2%</td><td>R02 去重</td></tr>
 *   <tr><td>下单时间无法解析</td><td>约 1.5%</td><td>R03 丢弃</td></tr>
 *   <tr><td>数量非法（0 / 负数 / 超大值）</td><td>约 0.5%</td><td>R04 丢弃</td></tr>
 *   <tr><td>单价非法（0 / 负数）</td><td>约 0.4%</td><td>R05 丢弃</td></tr>
 *   <tr><td>金额与"数量×单价-优惠"不一致</td><td>约 1.0%</td><td>R06 按公式重算修正</td></tr>
 *   <tr><td>订单状态非法枚举值</td><td>约 0.4%</td><td>R07 标准化为"其他"</td></tr>
 *   <tr><td>商品主数据缺失</td><td>约 0.3%</td><td>R08 关联维度后填充"未知类目"</td></tr>
 * </table>
 *
 * <p>随机种子固定，保证多次运行生成的数据完全一致，分析结果可复现。</p>
 */
public class MockDataGenerator {

    // ==================================================================
    // 一、静态数据字典
    // ==================================================================

    /** 商品类目字典 */
    private static class Category {
        final String id;
        final String name;
        final String[] brands;
        final String[] series;
        final String[] specs;
        final double minPrice;
        final double maxPrice;
        /** 成本价 / 售价 比例，用于生成成本价（仅供毛利分析演示） */
        final double costRatio;
        final double weight;

        Category(String id, String name, String[] brands, String[] series, String[] specs,
                 double minPrice, double maxPrice, double costRatio, double weight) {
            this.id = id;
            this.name = name;
            this.brands = brands;
            this.series = series;
            this.specs = specs;
            this.minPrice = minPrice;
            this.maxPrice = maxPrice;
            this.costRatio = costRatio;
            this.weight = weight;
        }
    }

    private static final Category[] CATEGORIES = {
            new Category("C01", "手机数码",
                    new String[]{"华为", "小米", "荣耀", "OPPO", "vivo", "Apple", "realme"},
                    new String[]{"Mate系列", "数字旗舰系列", "Magic系列", "Reno系列", "X系列", "Pro系列", "GT系列"},
                    new String[]{"8+128G", "12+256G", "256GB", "512GB", "16+512G"},
                    899, 8999, 0.78, 0.16),
            new Category("C02", "家用电器",
                    new String[]{"美的", "格力", "海尔", "苏泊尔", "九阳", "TCL"},
                    new String[]{"变频挂机空调", "滚筒洗衣机", "对开门冰箱", "智能电饭煲", "高速破壁机", "空气炸锅"},
                    new String[]{"1.5匹", "10公斤", "501L", "4L", "1.75L", "5L"},
                    199, 6999, 0.72, 0.11),
            new Category("C03", "服饰鞋包",
                    new String[]{"优衣库", "海澜之家", "李宁", "安踏", "太平鸟", "森马"},
                    new String[]{"男士休闲夹克", "女士针织衫", "运动连帽卫衣", "直筒牛仔裤", "轻便跑鞋", "通勤双肩包"},
                    new String[]{"S码", "M码", "L码", "XL码", "XXL码", "均码"},
                    59, 1299, 0.42, 0.15),
            new Category("C04", "食品生鲜",
                    new String[]{"三只松鼠", "良品铺子", "百草味", "伊利", "蒙牛", "金龙鱼"},
                    new String[]{"每日坚果", "手撕面包", "纯牛奶整箱", "常温酸奶", "食用调和油", "零食大礼包"},
                    new String[]{"500g", "750ml", "1L", "250ml*12", "1.8L", "2.5kg"},
                    9.9, 399, 0.65, 0.14),
            new Category("C05", "美妆个护",
                    new String[]{"珀莱雅", "完美日记", "花西子", "自然堂", "薇诺娜", "欧莱雅"},
                    new String[]{"双抗精华", "轻薄粉底液", "空气蜜粉", "水乳套装", "舒缓面膜", "氨基酸洁面乳"},
                    new String[]{"30ml", "50ml", "100ml", "10片装", "套装", "正装"},
                    29, 899, 0.35, 0.11),
            new Category("C06", "母婴玩具",
                    new String[]{"巴拉巴拉", "好孩子", "贝亲", "乐高", "费雪", "青蛙王子"},
                    new String[]{"婴儿连体衣", "儿童安全座椅", "宽口径奶瓶", "益智积木套装", "安抚玩偶", "婴儿洗衣液"},
                    new String[]{"66cm", "73cm", "0-4岁", "240ml", "标准装", "1L"},
                    25, 2499, 0.55, 0.07),
            new Category("C07", "家居家装",
                    new String[]{"顾家家居", "林氏木业", "全友", "罗莱", "水星家纺", "九牧"},
                    new String[]{"布艺三人沙发", "实木双人床", "纯棉四件套", "天然乳胶枕", "智能马桶", "定制衣柜"},
                    new String[]{"三人位", "1.8米", "1.5米床", "标准版", "加大版", "60cm"},
                    49, 8999, 0.60, 0.08),
            new Category("C08", "运动户外",
                    new String[]{"李宁", "安踏", "耐克", "阿迪达斯", "迪卡侬", "骆驼"},
                    new String[]{"减震跑鞋", "速干短袖T恤", "专业登山背包", "加厚瑜伽垫", "户外折叠椅", "防风冲锋衣"},
                    new String[]{"40码", "41码", "42码", "均码", "L码", "XL码"},
                    69, 1999, 0.45, 0.08),
            new Category("C09", "图书文娱",
                    new String[]{"人民邮电出版社", "中信出版社", "机械工业出版社", "读客文化", "果麦文化"},
                    new String[]{"计算机专业书", "经管励志书", "少儿启蒙绘本", "当代文学小说", "考试教辅书", "科普读物"},
                    new String[]{"平装版", "精装版", "套装3册", "大字版", "2026新版", "典藏版"},
                    19, 299, 0.50, 0.05),
            new Category("C10", "汽车用品",
                    new String[]{"3M", "车仆", "龟牌", "倍思", "米其林"},
                    new String[]{"全包围汽车脚垫", "车载快充充电器", "四季通用玻璃水", "高清行车记录仪", "汽车镀膜蜡", "静音轮胎"},
                    new String[]{"通用型", "2L", "双口版", "1080P", "4L", "205/55R16"},
                    29, 1599, 0.62, 0.05)
    };

    /** 省份-城市字典（含权重，模拟不同地域的消费能力差异） */
    private static final String[][] REGIONS = {
            {"广东省", "广州市,深圳市,东莞市,佛山市,珠海市", "0.14"},
            {"江苏省", "南京市,苏州市,无锡市,常州市,南通市", "0.10"},
            {"浙江省", "杭州市,宁波市,温州市,金华市,嘉兴市", "0.10"},
            {"山东省", "青岛市,济南市,烟台市,潍坊市,临沂市", "0.08"},
            {"北京市", "北京市", "0.06"},
            {"上海市", "上海市", "0.06"},
            {"河南省", "郑州市,洛阳市,南阳市,新乡市", "0.06"},
            {"四川省", "成都市,绵阳市,德阳市,宜宾市", "0.06"},
            {"湖北省", "武汉市,宜昌市,襄阳市", "0.05"},
            {"湖南省", "长沙市,株洲市,湘潭市", "0.045"},
            {"福建省", "福州市,厦门市,泉州市", "0.045"},
            {"河北省", "石家庄市,唐山市,保定市", "0.04"},
            {"安徽省", "合肥市,芜湖市", "0.035"},
            {"陕西省", "西安市,咸阳市", "0.035"},
            {"辽宁省", "沈阳市,大连市", "0.03"},
            {"重庆市", "重庆市", "0.03"},
            {"江西省", "南昌市,赣州市", "0.025"},
            {"山西省", "太原市", "0.02"},
            {"黑龙江省", "哈尔滨市", "0.02"},
            {"云南省", "昆明市", "0.02"}
    };

    /** 销售渠道 */
    private static final String[][] CHANNELS = {
            {"APP商城", "0.34"},
            {"微信小程序", "0.24"},
            {"天猫旗舰店", "0.16"},
            {"PC商城", "0.14"},
            {"京东自营", "0.12"}
    };

    /** 支付方式 */
    private static final String[][] PAY_TYPES = {
            {"支付宝", "0.40"},
            {"微信支付", "0.37"},
            {"银行卡", "0.12"},
            {"花呗", "0.07"},
            {"货到付款", "0.04"}
    };

    /** 订单状态（合法枚举） */
    private static final String[][] ORDER_STATUS = {
            {"已完成", "0.62"},
            {"已支付", "0.22"},
            {"待付款", "0.07"},
            {"已取消", "0.05"},
            {"已退款", "0.04"}
    };

    /** 非法订单状态样本（用于注入脏数据） */
    private static final String[] INVALID_STATUS = {"已妥投", "PAID", "待确认", "???"};

    /** 一天中各小时的订单分布权重（模拟"午间 + 夜间"两个下单高峰） */
    private static final double[] HOUR_WEIGHTS = {
            0.5, 0.3, 0.2, 0.15, 0.15, 0.2,
            0.6, 1.0, 1.6, 2.4, 3.2, 3.4,
            2.6, 2.0, 2.1, 2.2, 2.5, 3.0,
            3.4, 3.8, 3.9, 3.2, 1.8, 0.9
    };

    /** 每笔订单包含的商品行数权重（1~3 行，平均约 1.85 行） */
    private static final double[] LINES_PER_ORDER_WEIGHTS = {0.40, 0.35, 0.25};

    private static final DateTimeFormatter FMT_A = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FMT_B = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm");
    private static final DateTimeFormatter FMT_C = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** 用户池规模，控制复购率（约 6 万用户） */
    private static final int USER_POOL_SIZE = 60000;

    // ==================================================================
    // 二、生成结果载体
    // ==================================================================

    public static class Result {
        public final Path orderFile;
        public final Path productFile;
        public final int productCount;
        public final long orderRows;
        public final long sampleRows;

        Result(Path orderFile, Path productFile, int productCount, long orderRows, long sampleRows) {
            this.orderFile = orderFile;
            this.productFile = productFile;
            this.productCount = productCount;
            this.orderRows = orderRows;
            this.sampleRows = sampleRows;
        }

        @Override
        public String toString() {
            return String.format("商品维度 %d 条 -> %s；订单明细 %d 行 -> %s",
                    productCount, productFile, orderRows, orderFile);
        }
    }

    // ==================================================================
    // 三、生成逻辑
    // ==================================================================

    public static void main(String[] args) throws IOException {
        JobConfig config = JobConfig.load();
        Result r = generate(config);
        System.out.println("[MockDataGenerator] " + r);
    }

    public static Result generate(JobConfig config) throws IOException {
        Random random = new Random(config.mockSeed());

        // 数据生成始终写本地目录：集群模式下由部署脚本再把文件上传到 HDFS
        Path outDir = Paths.get(config.mockOutputDir()).toAbsolutePath().normalize();
        Files.createDirectories(outDir);
        Path orderFile = outDir.resolve("ods_order_detail.csv");
        Path productFile = outDir.resolve("ods_product.csv");

        // ---------- 1. 生成商品维度 ----------
        List<String[]> products = buildProducts(config.mockProductCount(), random);
        // 故意"丢"掉少量商品主数据，用于验证 R08 维度关联缺失的处理能力
        int missingCount = Math.max(1, products.size() * 3 / 1000);
        List<String> missingProductIds = new ArrayList<>();
        for (int i = 0; i < missingCount; i++) {
            missingProductIds.add(products.remove(products.size() - 1 - i)[0]);
        }

        writeProductFile(productFile, products);

        // ---------- 2. 生成订单明细 ----------
        long[] stat = new long[1];
        writeOrderFile(orderFile, config, random, missingProductIds, stat);

        // ---------- 3. 输出样例数据（用于提交到代码仓库） ----------
        long sampleRows = writeSampleFiles(config, orderFile, productFile);

        return new Result(orderFile, productFile, products.size(), stat[0], sampleRows);
    }

    /** 构建商品维度数据 */
    private static List<String[]> buildProducts(int count, Random random) {
        List<String[]> list = new ArrayList<>(count);
        double[] weights = new double[CATEGORIES.length];
        for (int i = 0; i < CATEGORIES.length; i++) {
            weights[i] = CATEGORIES[i].weight;
        }
        int seq = 1;
        for (int i = 0; i < count; i++) {
            Category c = CATEGORIES[weightedIndex(weights, random)];
            String brand = c.brands[random.nextInt(c.brands.length)];
            String series = c.series[random.nextInt(c.series.length)];
            String spec = c.specs[random.nextInt(c.specs.length)];
            String productId = String.format("P%05d", seq++);
            String productName = brand + " " + series + " " + spec;

            double price = round2(c.minPrice + random.nextDouble() * (c.maxPrice - c.minPrice));
            double costPrice = round2(price * (c.costRatio + (random.nextDouble() - 0.5) * 0.08));

            LocalDate launch = LocalDate.of(2023, 1, 1).plusDays(random.nextInt(1200));

            list.add(new String[]{
                    productId,
                    productName,
                    c.id,
                    c.name,
                    brand,
                    String.format("%.2f", costPrice),
                    launch.toString()
            });
        }
        return list;
    }

    private static void writeProductFile(Path file, List<String[]> products) throws IOException {
        try (BufferedWriter w = newWriter(file)) {
            w.write("product_id,product_name,category_id,category_name,brand,cost_price,launch_date");
            w.newLine();
            for (String[] p : products) {
                w.write(String.join(",", p));
                w.newLine();
            }
        }
        System.out.println("[MockDataGenerator] 商品维度写入完成：" + file + "（" + products.size() + " 条）");
    }

    private static void writeOrderFile(Path file, JobConfig config, Random random,
                                       List<String> missingProductIds, long[] rowCounter) throws IOException {
        LocalDate start = LocalDate.parse(config.dataStartDate());
        LocalDate end = LocalDate.parse(config.dataEndDate());
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        if (days <= 0) {
            throw new IllegalArgumentException("数据日期范围不合法: " + start + " ~ " + end);
        }

        // 目标行数按 98.8% 计算，注入的重复数据会把总量补足到目标行数附近。
        // 每笔订单平均 1.85 行商品明细，因此订单数 = 目标行数 / 1.85
        long targetRows = config.mockRowCount();
        long totalOrders = Math.max(days, (long) (targetRows * 0.988 / 1.85));
        int productCount = config.mockProductCount() - Math.max(1, config.mockProductCount() * 3 / 1000);

        // 按"日期权重"分配每天的订单量
        double[] dayWeights = new double[(int) days];
        for (int d = 0; d < days; d++) {
            LocalDate date = start.plusDays(d);
            double weekendFactor = (date.getDayOfWeek().getValue() >= 6) ? 1.32 : 1.0;
            double growthFactor = 1.0 + 0.004 * d;
            dayWeights[d] = weekendFactor * growthFactor;
        }
        double dayWeightSum = 0;
        for (double w : dayWeights) {
            dayWeightSum += w;
        }

        double[] categoryWeights = new double[CATEGORIES.length];
        for (int i = 0; i < CATEGORIES.length; i++) {
            categoryWeights[i] = CATEGORIES[i].weight;
        }

        long orderNo = 0;
        long rows = 0;
        try (BufferedWriter w = newWriter(file)) {
            w.write("order_id,order_time,user_id,product_id,channel,province,city,"
                    + "quantity,unit_price,discount_amount,pay_amount,order_status,pay_type");
            w.newLine();

            List<Category> categoryList = java.util.Arrays.asList(CATEGORIES);

            for (int d = 0; d < days; d++) {
                LocalDate date = start.plusDays(d);
                long ordersOfDay = Math.round(totalOrders * (dayWeights[d] / dayWeightSum));

                for (long o = 0; o < ordersOfDay; o++) {
                    orderNo++;
                    String orderId = String.format("SO%s%06d", date.format(DateTimeFormatter.ofPattern("yyyyMMdd")), orderNo % 1000000);
                    String userId = String.format("U%06d", random.nextInt(USER_POOL_SIZE) + 1);
                    LocalDateTime orderTime = randomOrderTime(date, random);
                    String channel = pick(CHANNELS, random);
                    int regionIdx = weightedIndexByColumn(REGIONS, 2, random);
                    String province = REGIONS[regionIdx][0];
                    String[] cities = REGIONS[regionIdx][1].split(",");
                    String city = cities[random.nextInt(cities.length)];
                    String status = pick(ORDER_STATUS, random);
                    String payType = pick(PAY_TYPES, random);

                    int lines = 1 + weightedIndex(LINES_PER_ORDER_WEIGHTS, random);

                    for (int li = 0; li < lines; li++) {
                        int cIdx = weightedIndex(categoryWeights, random);
                        Category c = categoryList.get(cIdx);

                        String productId;
                        // 约 0.3% 的订单行引用"不存在"的商品，用于验证维度关联缺失处理
                        if (!missingProductIds.isEmpty() && random.nextDouble() < 0.003) {
                            productId = missingProductIds.get(random.nextInt(missingProductIds.size()));
                        } else {
                            productId = String.format("P%05d", random.nextInt(productCount) + 1);
                        }

                        int quantity = pickQuantity(random);
                        double unitPrice = round2(c.minPrice + random.nextDouble() * (c.maxPrice - c.minPrice));
                        double gross = round2(quantity * unitPrice);
                        double discount = random.nextDouble() < 0.55
                                ? 0.0
                                : round2(gross * (0.05 + random.nextDouble() * 0.20));
                        double payAmount = round2(gross - discount);

                        String timeText = formatOrderTime(orderTime, random);
                        String qtyText = String.valueOf(quantity);
                        String priceText = String.format("%.2f", unitPrice);
                        String discountText = String.format("%.2f", discount);
                        String payText = String.format("%.2f", payAmount);
                        String statusText = status;
                        String payTypeText = payType;
                        String provinceText = province;
                        String cityText = city;

                        // ---- 注入脏数据 ----
                        // R01 空值
                        if (random.nextDouble() < 0.010) {
                            provinceText = "";
                        }
                        if (random.nextDouble() < 0.012) {
                            cityText = "";
                        }
                        if (random.nextDouble() < 0.012) {
                            payTypeText = "";
                        }
                        // R04 数量非法
                        if (random.nextDouble() < 0.005) {
                            int[] bad = {0, -1, 99999};
                            qtyText = String.valueOf(bad[random.nextInt(bad.length)]);
                        }
                        // R05 单价非法
                        if (random.nextDouble() < 0.004) {
                            priceText = random.nextBoolean() ? "0.00" : "-" + priceText;
                        }
                        // R06 金额不一致
                        if (random.nextDouble() < 0.010) {
                            payText = String.format("%.2f", round2(payAmount * (1.2 + random.nextDouble() * 0.5)));
                        }
                        // R07 状态非法
                        if (random.nextDouble() < 0.004) {
                            statusText = INVALID_STATUS[random.nextInt(INVALID_STATUS.length)];
                        }

                        String line = String.join(",", orderId, timeText, userId, productId, channel,
                                provinceText, cityText, qtyText, priceText, discountText, payText,
                                statusText, payTypeText);
                        w.write(line);
                        w.newLine();
                        rows++;

                        // R02 重复记录：紧邻写入同一条记录
                        if (random.nextDouble() < 0.012) {
                            w.write(line);
                            w.newLine();
                            rows++;
                        }
                    }
                }
            }
        }
        rowCounter[0] = rows;
        System.out.println("[MockDataGenerator] 订单明细写入完成：" + file + "（" + rows + " 行，"
                + days + " 天，约 " + orderNo + " 笔订单）");
    }

    /** 输出样例数据到 data/sample 目录，便于随代码一起提交 */
    private static long writeSampleFiles(JobConfig config, Path orderFile, Path productFile) throws IOException {
        Path sampleDir = Paths.get(config.get("data.sample.dir", "data/sample")).toAbsolutePath().normalize();
        Files.createDirectories(sampleDir);
        long limit = config.getLong("mock.sample.rows", 2000);

        Path orderSample = sampleDir.resolve("ods_order_detail_sample.csv");
        Path productSample = sampleDir.resolve("ods_product_sample.csv");

        List<String> orderLines = Files.readAllLines(orderFile, StandardCharsets.UTF_8);
        List<String> productLines = Files.readAllLines(productFile, StandardCharsets.UTF_8);

        writeLimited(orderSample, orderLines, (int) Math.min(limit + 1, orderLines.size()));
        writeLimited(productSample, productLines, Math.min(61, productLines.size()));

        System.out.println("[MockDataGenerator] 样例数据写入完成：" + sampleDir);
        return Math.min(limit, orderLines.size() - 1);
    }

    private static void writeLimited(Path path, List<String> lines, int maxLines) throws IOException {
        try (BufferedWriter w = newWriter(path)) {
            for (int i = 0; i < maxLines && i < lines.size(); i++) {
                w.write(lines.get(i));
                w.newLine();
            }
        }
    }

    // ==================================================================
    // 四、工具方法
    // ==================================================================

    private static BufferedWriter newWriter(Path file) throws IOException {
        return new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file.toFile()), StandardCharsets.UTF_8), 1 << 16);
    }

    /** 按小时权重重随机出一个下单时刻 */
    private static LocalDateTime randomOrderTime(LocalDate date, Random random) {
        int hour = weightedIndex(HOUR_WEIGHTS, random);
        int minute = random.nextInt(60);
        int second = random.nextInt(60);
        return LocalDateTime.of(date, LocalTime.of(hour, minute, second));
    }

    /**
     * 按概率把下单时间输出成多种格式，其中约 1.5% 为无法解析的脏数据。
     */
    private static String formatOrderTime(LocalDateTime time, Random random) {
        double r = random.nextDouble();
        if (r < 0.015) {
            String[] bad = {
                    "2026/13/45 25:61:00",
                    "0000-00-00 00:00:00",
                    "invalid_time",
                    "2026-06-31 10:00:00",
                    ""
            };
            return bad[random.nextInt(bad.length)];
        } else if (r < 0.125) {
            return time.format(FMT_B);
        } else if (r < 0.160) {
            return time.format(FMT_C);
        }
        return time.format(FMT_A);
    }

    private static int pickQuantity(Random random) {
        double[] w = {0.72, 0.18, 0.06, 0.025, 0.015};
        return weightedIndex(w, random) + 1;
    }

    /** 简单加权随机：weights 不必归一化 */
    private static int weightedIndex(double[] weights, Random random) {
        double total = 0;
        for (double w : weights) {
            total += w;
        }
        double r = random.nextDouble() * total;
        double acc = 0;
        for (int i = 0; i < weights.length; i++) {
            acc += weights[i];
            if (r < acc) {
                return i;
            }
        }
        return weights.length - 1;
    }

    /** 从 [名称, 权重] 型字典中加权取值 */
    private static String pick(String[][] dict, Random random) {
        double[] w = new double[dict.length];
        for (int i = 0; i < dict.length; i++) {
            w[i] = Double.parseDouble(dict[i][1]);
        }
        return dict[weightedIndex(w, random)][0];
    }

    /** 字典中权重位于指定列时使用 */
    private static int weightedIndexByColumn(String[][] dict, int weightCol, Random random) {
        double[] w = new double[dict.length];
        for (int i = 0; i < dict.length; i++) {
            w[i] = Double.parseDouble(dict[i][weightCol]);
        }
        return weightedIndex(w, random);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
