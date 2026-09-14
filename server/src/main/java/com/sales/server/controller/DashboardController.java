package com.sales.server.controller;

import com.sales.server.common.Result;
import com.sales.server.dto.AnalysisQuery;
import com.sales.server.service.AnalysisService;
import com.sales.server.vo.ChannelCategoryVO;
import com.sales.server.vo.FilterMetaVO;
import com.sales.server.vo.KpiCardVO;
import com.sales.server.vo.MetricItemVO;
import com.sales.server.vo.ProductRankVO;
import com.sales.server.vo.TrendPointVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.api.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.List;

/**
 * 看板数据接口。
 *
 * <p>所有接口共用同一组筛选条件（日期范围、渠道、类目、省份、支付方式），
 * 前端切换筛选条件时同时刷新所有图表，实现图表联动。</p>
 */
@Tag(name = "01-看板数据", description = "核心指标、销售趋势、多维排行与分布")
@RestController
@RequestMapping("/api")
public class DashboardController {

    @Resource
    private AnalysisService analysisService;

    @Operation(summary = "核心指标总览", description = "返回 GMV、下单量、有效订单量、客单价等 9 项核心指标")
    @GetMapping("/overview")
    public Result<List<KpiCardVO>> overview(@ParameterObject AnalysisQuery query) {
        return Result.success(analysisService.overview(query));
    }

    @Operation(summary = "销售趋势", description = "按天返回 GMV、订单量、用户数、退款率等指标，用于折线图")
    @GetMapping("/trend")
    public Result<List<TrendPointVO>> trend(@ParameterObject AnalysisQuery query) {
        return Result.success(analysisService.trend(query));
    }

    @Operation(summary = "类目排行", description = "按类目分组统计成交金额并计算占比与排名")
    @GetMapping("/category/topn")
    public Result<List<MetricItemVO>> categoryTopN(@ParameterObject AnalysisQuery query) {
        return Result.success(analysisService.categoryTopN(query));
    }

    @Operation(summary = "商品排行", description = "按商品分组统计成交金额，返回 TopN")
    @GetMapping("/product/topn")
    public Result<List<ProductRankVO>> productTopN(@ParameterObject AnalysisQuery query) {
        return Result.success(analysisService.productRank(query));
    }

    @Operation(summary = "渠道分布", description = "按销售渠道统计成交金额与占比，用于饼图")
    @GetMapping("/channel/dist")
    public Result<List<MetricItemVO>> channelDist(@ParameterObject AnalysisQuery query) {
        return Result.success(analysisService.channelDist(query));
    }

    @Operation(summary = "支付方式分布", description = "按支付方式统计成交金额与占比，用于饼图")
    @GetMapping("/paytype/dist")
    public Result<List<MetricItemVO>> paytypeDist(@ParameterObject AnalysisQuery query) {
        return Result.success(analysisService.paytypeDist(query));
    }

    @Operation(summary = "省份排行", description = "按省份统计成交金额，返回 TopN")
    @GetMapping("/region/topn")
    public Result<List<MetricItemVO>> regionTopN(@ParameterObject AnalysisQuery query) {
        return Result.success(analysisService.regionTopN(query));
    }

    @Operation(summary = "渠道与类目交叉对比", description = "分组对比分析：同一类目在不同渠道下的成交表现")
    @GetMapping("/channel-category")
    public Result<List<ChannelCategoryVO>> channelCategory(@ParameterObject AnalysisQuery query) {
        return Result.success(analysisService.channelCategory(query));
    }

    @Operation(summary = "筛选条件候选项", description = "返回数据日期范围以及渠道、类目、省份、支付方式的可选值")
    @GetMapping("/meta/filters")
    public Result<FilterMetaVO> filters() {
        return Result.success(analysisService.filterMeta());
    }
}
