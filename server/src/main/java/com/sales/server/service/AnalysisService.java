package com.sales.server.service;

import com.sales.server.dto.AnalysisQuery;
import com.sales.server.entity.AdsOverview;
import com.sales.server.mapper.AnalysisMapper;
import com.sales.server.vo.ChannelCategoryVO;
import com.sales.server.vo.ConsistencyItemVO;
import com.sales.server.vo.FilterMetaVO;
import com.sales.server.vo.KpiCardVO;
import com.sales.server.vo.MetricItemVO;
import com.sales.server.vo.ProductRankVO;
import com.sales.server.vo.TrendPointVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 指标分析服务。
 *
 * <p>查询策略：</p>
 * <ol>
 *   <li><b>默认视图</b>（仅按日期范围筛选）：读取离线作业预计算的 ADS 结果表，响应快；</li>
 *   <li><b>多维筛选</b>（含渠道 / 类目 / 省份 / 支付方式）：改为对 DWD 明细做即席聚合，
 *       保证任意筛选组合下的指标都能正确联动；</li>
 *   <li>两条路径口径一致，由 {@link #consistency()} 交叉校验。</li>
 * </ol>
 */
@Slf4j
@Service
public class AnalysisService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int AMOUNT_SCALE = 2;
    private static final int RATIO_SCALE = 2;

    /**
     * 金额单位，来自配置 {@code app.currency.unit}。
     *
     * <p>必须与离线作业的 {@code data.currency.unit} 保持一致：
     * 模拟数据是人民币（元），Olist 真实数据集是巴西雷亚尔（BRL）。</p>
     */
    @Value("${app.currency.unit:元}")
    private String currencyUnit;

    @Resource
    private AnalysisMapper analysisMapper;

    // ==================================================================
    // 核心指标总览
    // ==================================================================

    public List<KpiCardVO> overview(AnalysisQuery query) {
        prepare(query);
        // 只有「完全没有任何筛选」时，才可以直接返回全周期预计算快照：
        // ads_overview 是全周期算好的定值，一旦请求带了日期范围，它的数值就不再对应请求区间。
        //
        // 早期实现只要没有维度筛选就走快照，导致「日期范围对总览指标完全不起作用」。
        // 现在改为：只要带了日期范围或维度筛选，就回明细即席聚合，原因是：
        //   ① 去重指标（下单用户数）不能用每日去重值累加 —— 同一用户跨天会重复计数；
        //   ② 预计算快照没有区间概念，结构上无法响应 startDate/endDate。
        if (!hasAnyFilter(query)) {
            List<AdsOverview> presets = analysisMapper.selectOverviewFromAds();
            if (presets != null && !presets.isEmpty()) {
                List<KpiCardVO> cards = new ArrayList<>(presets.size());
                for (AdsOverview o : presets) {
                    KpiCardVO card = new KpiCardVO();
                    card.setCode(o.getKpiCode());
                    card.setName(o.getKpiName());
                    card.setValue(o.getKpiValue() == null ? BigDecimal.ZERO : o.getKpiValue());
                    card.setUnit(o.getKpiUnit());
                    card.setDesc(o.getKpiDesc());
                    card.setSource("PRECOMPUTE");
                    cards.add(card);
                }
                return cards;
            }
            log.warn("预计算指标表为空，自动切换为即席聚合");
        }
        return overviewFromDwd(query);
    }

    /**
     * 按查询区间对 DWD 明细做即席聚合，得到全部 9 项总览指标。
     *
     * <p>区间由 SQL 的 WHERE 透传到明细层，因此：</p>
     * <ul>
     *   <li>可加指标（GMV / 销售件数 / 订单量）等于区间内各日之和；</li>
     *   <li>去重指标（下单用户数）在<b>整个区间内去重一次</b>，
     *       而不是把每日的去重值相加 —— 这正是不能简单累计每日值的原因。</li>
     * </ul>
     */
    private List<KpiCardVO> overviewFromDwd(AnalysisQuery query) {
        Map<String, Object> m = analysisMapper.selectOverviewFromDwd(query);
        BigDecimal gmv = decimal(m, "gmv");
        long orderCnt = longValue(m, "orderCnt");
        long validOrderCnt = longValue(m, "validOrderCnt");
        long salesQty = longValue(m, "salesQty");
        long buyerCnt = longValue(m, "buyerCnt");
        BigDecimal gross = decimal(m, "grossAmount");
        BigDecimal discount = decimal(m, "discountAmount");
        long refundOrderCnt = longValue(m, "refundOrderCnt");

        List<KpiCardVO> cards = new ArrayList<>();
        cards.add(card("GMV", "成交金额", gmv, currencyUnit, "有效订单（已完成/已支付）的实付金额之和"));
        cards.add(card("ORDER_CNT", "下单量", BigDecimal.valueOf(orderCnt), "单", "去重后的订单编号数量，含取消与退款订单"));
        cards.add(card("VALID_ORDER_CNT", "有效订单量", BigDecimal.valueOf(validOrderCnt), "单", "订单状态为已完成或已支付的订单数量"));
        cards.add(card("SALES_QTY", "销售件数", BigDecimal.valueOf(salesQty), "件", "有效订单中的商品购买数量之和"));
        cards.add(card("BUYER_CNT", "下单用户数", BigDecimal.valueOf(buyerCnt), "人", "产生有效订单的去重用户数量"));
        cards.add(card("AVG_ORDER_AMOUNT", "客单价", divide(gmv, BigDecimal.valueOf(validOrderCnt)), currencyUnit, "GMV ÷ 有效订单量"));
        cards.add(card("AVG_ITEM_PRICE", "件单价", divide(gmv, BigDecimal.valueOf(salesQty)), currencyUnit, "GMV ÷ 销售件数"));
        cards.add(card("REFUND_RATE", "退款率",
                divide(BigDecimal.valueOf(refundOrderCnt).multiply(HUNDRED), BigDecimal.valueOf(orderCnt)),
                "%", "退款订单量 ÷ 下单量"));
        cards.add(card("DISCOUNT_RATE", "折扣率",
                divide(discount.multiply(HUNDRED), gross), "%", "优惠金额 ÷ 原价金额"));
        return cards;
    }

    // ==================================================================
    // 趋势
    // ==================================================================

    public List<TrendPointVO> trend(AnalysisQuery query) {
        prepare(query);
        if (!hasDimensionFilter(query)) {
            List<TrendPointVO> fromAds = analysisMapper.selectTrendFromAds(query);
            if (fromAds != null && !fromAds.isEmpty()) {
                return fromAds;
            }
            log.warn("预计算趋势表为空，自动切换为即席聚合");
        }
        return analysisMapper.selectTrendFromDwd(query);
    }

    // ==================================================================
    // 维度统计
    // ==================================================================

    public List<MetricItemVO> categoryTopN(AnalysisQuery query) {
        prepare(query);
        List<MetricItemVO> list = analysisMapper.selectCategoryStat(query);
        return withRatioAndRank(list, query);
    }

    public List<MetricItemVO> channelDist(AnalysisQuery query) {
        prepare(query);
        List<MetricItemVO> list = analysisMapper.selectChannelStat(query);
        return withRatioAndRank(list, query);
    }

    public List<MetricItemVO> paytypeDist(AnalysisQuery query) {
        prepare(query);
        List<MetricItemVO> list = analysisMapper.selectPaytypeStat(query);
        return withRatioAndRank(list, query);
    }

    public List<MetricItemVO> regionTopN(AnalysisQuery query) {
        prepare(query);
        List<MetricItemVO> list = analysisMapper.selectRegionStat(query);
        return withRatioAndRank(list, query);
    }

    public List<ProductRankVO> productRank(AnalysisQuery query) {
        prepare(query);
        List<ProductRankVO> list;
        if (!hasDimensionFilter(query)) {
            // 默认视图走预计算表：数据量从 19 万行降到 1.8 万行，响应快一个数量级
            list = analysisMapper.selectProductRankFromAds(query);
            if (list == null || list.isEmpty()) {
                log.warn("预计算商品排行表为空，自动切换为即席聚合");
                list = analysisMapper.selectProductRank(query);
            }
        } else {
            // 带渠道/类目/省份等维度筛选时，预计算表不含这些维度，改为即席聚合
            list = analysisMapper.selectProductRank(query);
        }
        if (list == null) {
            return new ArrayList<>();
        }
        int rank = 1;
        for (ProductRankVO vo : list) {
            vo.setRankNo(rank++);
            if (vo.getGmv() == null) {
                vo.setGmv(BigDecimal.ZERO);
            }
            vo.setGmv(vo.getGmv().setScale(AMOUNT_SCALE, RoundingMode.HALF_UP));
            if (vo.getSalesQty() == null) {
                vo.setSalesQty(0L);
            }
            if (vo.getOrderCnt() == null) {
                vo.setOrderCnt(0L);
            }
        }
        return list;
    }

    public List<ChannelCategoryVO> channelCategory(AnalysisQuery query) {
        prepare(query);
        List<ChannelCategoryVO> list = analysisMapper.selectChannelCategory(query);
        if (list == null) {
            return new ArrayList<>();
        }
        for (ChannelCategoryVO vo : list) {
            if (vo.getGmv() == null) {
                vo.setGmv(BigDecimal.ZERO);
            }
            vo.setGmv(vo.getGmv().setScale(AMOUNT_SCALE, RoundingMode.HALF_UP));
        }
        return list;
    }

    // ==================================================================
    // 数据一致性校验（数据正确性测试）
    // ==================================================================

    public List<ConsistencyItemVO> consistency() {
        List<ConsistencyItemVO> items = new ArrayList<>();
        Map<String, Object> ads = analysisMapper.sumTrendFromAds();
        Map<String, Object> dwd = analysisMapper.sumFromDwd();

        items.add(check("GMV 合计", decimal(ads, "gmv"), decimal(dwd, "gmv"), currencyUnit, true));
        items.add(check("销售件数合计",
                decimal(ads, "salesQty"), decimal(dwd, "salesQty"), "件", false));
        items.add(check("有效订单量合计",
                decimal(ads, "validOrderCnt"), decimal(dwd, "validOrderCnt"), "单", false));
        items.add(check("类目 GMV 合计",
                analysisMapper.sumCategoryGmvFromAds(), analysisMapper.sumCategoryGmvFromDwd(), currencyUnit, true));

        for (ConsistencyItemVO item : items) {
            log.info("一致性校验 [{}] 预计算={} 明细汇总={} 结果={}",
                    item.getItem(), item.getAdsValue(), item.getDetailValue(), item.getPassed() ? "通过" : "不一致");
        }
        return items;
    }

    private ConsistencyItemVO check(String item, BigDecimal ads, BigDecimal dwd, String unit, boolean amount) {
        ConsistencyItemVO vo = new ConsistencyItemVO();
        vo.setItem(item);
        vo.setAdsValue(ads == null ? BigDecimal.ZERO : ads);
        vo.setDetailValue(dwd == null ? BigDecimal.ZERO : dwd);
        BigDecimal diff = vo.getAdsValue().subtract(vo.getDetailValue()).abs();
        vo.setDiff(diff.setScale(amount ? AMOUNT_SCALE : 0, RoundingMode.HALF_UP));
        vo.setUnit(unit);
        BigDecimal tolerance = amount ? BigDecimal.valueOf(0.01) : BigDecimal.ZERO;
        vo.setPassed(diff.compareTo(tolerance) <= 0);
        return vo;
    }

    // ==================================================================
    // 筛选条件候选项
    // ==================================================================

    public FilterMetaVO filterMeta() {
        FilterMetaVO vo = new FilterMetaVO();
        try {
            Map<String, Object> range = analysisMapper.selectDateRange();
            if (range != null) {
                vo.setMinDate(stringValue(range.get("minDate")));
                vo.setMaxDate(stringValue(range.get("maxDate")));
            }
            vo.setChannels(nullSafe(analysisMapper.selectChannels()));
            vo.setCategories(nullSafe(analysisMapper.selectCategories()));
            vo.setProvinces(nullSafe(analysisMapper.selectProvinces()));
            vo.setPayTypes(nullSafe(analysisMapper.selectPayTypes()));
        } catch (Exception e) {
            log.warn("读取筛选条件失败，可能离线作业尚未执行: {}", e.getMessage());
        }
        return vo;
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    /** 统一补全查询参数默认值 */
    private void prepare(AnalysisQuery query) {
        query.normalize();
        query.setLimit(query.limitOrDefault());
    }

    /** 是否带维度筛选（日期范围不算维度） */
    private boolean hasDimensionFilter(AnalysisQuery q) {
        return isNotBlank(q.getChannel())
                || isNotBlank(q.getCategoryName())
                || isNotBlank(q.getProvince())
                || isNotBlank(q.getPayType());
    }

    /** 是否带日期范围筛选 */
    private boolean hasDateFilter(AnalysisQuery q) {
        return isNotBlank(q.getStartDate()) || isNotBlank(q.getEndDate());
    }

    /** 是否带任何筛选（日期范围或维度） */
    private boolean hasAnyFilter(AnalysisQuery q) {
        return hasDateFilter(q) || hasDimensionFilter(q);
    }

    /**
     * 补齐「占比」与「排名」。
     *
     * <p><b>占比的分母取"当前筛选条件下的全部 GMV 合计"，而不是入参列表之和。</b>
     * 各维度统计都带 LIMIT，返回的只是 TopN；若用返回列表之和作分母，
     * TopN 的占比必然凑成 100%（例如类目 Top3 显示 36% + 34% + 30%），失去业务含义。
     * 分母通过 selectTotalGmv 查询，与分子复用同一套筛选条件，保证同源。</p>
     */
    private List<MetricItemVO> withRatioAndRank(List<MetricItemVO> list, AnalysisQuery query) {
        if (list == null || list.isEmpty()) {
            return new ArrayList<>();
        }
        for (MetricItemVO item : list) {
            if (item.getGmv() == null) {
                item.setGmv(BigDecimal.ZERO);
            }
            item.setGmv(item.getGmv().setScale(AMOUNT_SCALE, RoundingMode.HALF_UP));
            if (item.getSalesQty() == null) {
                item.setSalesQty(0L);
            }
            if (item.getOrderCnt() == null) {
                item.setOrderCnt(0L);
            }
            if (item.getBuyerCnt() == null) {
                item.setBuyerCnt(0L);
            }
        }
        BigDecimal total = analysisMapper.selectTotalGmv(query);
        if (total == null) {
            total = BigDecimal.ZERO;
        }
        int rank = 1;
        for (MetricItemVO item : list) {
            item.setRankNo(rank++);
            BigDecimal ratio = total.compareTo(BigDecimal.ZERO) == 0
                    ? BigDecimal.ZERO
                    : item.getGmv().multiply(HUNDRED).divide(total, RATIO_SCALE, RoundingMode.HALF_UP);
            item.setRatio(ratio);
        }
        return list;
    }

    private KpiCardVO card(String code, String name, BigDecimal value, String unit, String desc) {
        KpiCardVO card = new KpiCardVO();
        card.setCode(code);
        card.setName(name);
        card.setValue(value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP));
        card.setUnit(unit);
        card.setDesc(desc);
        card.setSource("ONDEMAND");
        return card;
    }

    private static BigDecimal divide(BigDecimal a, BigDecimal b) {
        if (b == null || b.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        }
        return a.divide(b, AMOUNT_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal decimal(Map<String, Object> map, String key) {
        if (map == null) {
            return BigDecimal.ZERO;
        }
        Object v = map.get(key);
        if (v == null) {
            return BigDecimal.ZERO;
        }
        if (v instanceof BigDecimal) {
            return (BigDecimal) v;
        }
        if (v instanceof Number) {
            return BigDecimal.valueOf(((Number) v).doubleValue());
        }
        return new BigDecimal(v.toString());
    }

    private static long longValue(Map<String, Object> map, String key) {
        if (map == null) {
            return 0L;
        }
        Object v = map.get(key);
        if (v == null) {
            return 0L;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String stringValue(Object v) {
        return v == null ? null : v.toString();
    }

    private static List<String> nullSafe(List<String> list) {
        return list == null ? new ArrayList<>() : list;
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
