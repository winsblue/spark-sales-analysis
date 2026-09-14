package com.sales.server.mapper;

import com.sales.server.dto.AnalysisQuery;
import com.sales.server.entity.AdsOverview;
import com.sales.server.vo.ChannelCategoryVO;
import com.sales.server.vo.MetricItemVO;
import com.sales.server.vo.ProductRankVO;
import com.sales.server.vo.TrendPointVO;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 分析查询 Mapper。
 *
 * <p>数据来源分两条路径：</p>
 * <ul>
 *   <li><b>ADS 预计算路径</b>：读取离线作业预先算好的指标结果表，用于"无维度筛选"的默认视图，响应快；</li>
 *   <li><b>DWD 即席聚合路径</b>：直接对清洗后的明细表做分组聚合，用于任意维度组合的筛选与下钻。</li>
 * </ul>
 * <p>两条路径的指标口径完全一致，并由"数据一致性校验"接口交叉验证。</p>
 */
public interface AnalysisMapper {

    // ==================================================================
    // 一、ADS 预计算路径（默认视图，支持日期范围筛选）
    // ==================================================================

    /** 趋势曲线：读取预计算表 */
    List<TrendPointVO> selectTrendFromAds(@Param("q") AnalysisQuery query);

    /** 核心指标：读取预计算表 */
    List<AdsOverview> selectOverviewFromAds();

    /**
     * 商品排行：读取预计算表。
     *
     * <p>商品排行是最重的维度查询（分组键含多个长字符串列，且需要去重计数），
     * 直接扫明细表在 InnoDB 缓冲池较小的环境下可能达到数十秒；
     * 预计算表只有"天 × 商品"约 1.8 万行，扫描量下降一个数量级。</p>
     */
    List<ProductRankVO> selectProductRankFromAds(@Param("q") AnalysisQuery query);

    // ==================================================================
    // 二、DWD 即席聚合路径（支持日期、渠道、类目、省份任意组合）
    // ==================================================================

    /** 趋势曲线：按日聚合明细 */
    List<TrendPointVO> selectTrendFromDwd(@Param("q") AnalysisQuery query);

    /** 核心指标：按条件聚合明细，返回单行多列 */
    Map<String, Object> selectOverviewFromDwd(@Param("q") AnalysisQuery query);

    /** 类目排行 / 分布 */
    List<MetricItemVO> selectCategoryStat(@Param("q") AnalysisQuery query);

    /** 商品排行 */
    List<ProductRankVO> selectProductRank(@Param("q") AnalysisQuery query);

    /** 渠道分布 */
    List<MetricItemVO> selectChannelStat(@Param("q") AnalysisQuery query);

    /** 支付方式分布 */
    List<MetricItemVO> selectPaytypeStat(@Param("q") AnalysisQuery query);

    /** 省份排行 */
    List<MetricItemVO> selectRegionStat(@Param("q") AnalysisQuery query);

    /** 渠道 × 类目 交叉对比 */
    List<ChannelCategoryVO> selectChannelCategory(@Param("q") AnalysisQuery query);

    // ==================================================================
    // 三、数据一致性校验：对比 ADS 预计算值与 DWD 实时汇总值
    // ==================================================================

    Map<String, Object> sumTrendFromAds();

    Map<String, Object> sumFromDwd();

    BigDecimal sumCategoryGmvFromAds();

    BigDecimal sumCategoryGmvFromDwd();

    // ==================================================================
    // 四、筛选条件候选项（从明细表取维度取值）
    // ==================================================================

    Map<String, Object> selectDateRange();

    List<String> selectChannels();

    List<String> selectCategories();

    List<String> selectProvinces();

    List<String> selectPayTypes();
}
