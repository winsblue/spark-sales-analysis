package com.sales.server.controller;

import com.sales.server.common.Result;
import com.sales.server.entity.AdsCleanStat;
import com.sales.server.entity.EtlJobLog;
import com.sales.server.service.AnalysisService;
import com.sales.server.service.MonitorService;
import com.sales.server.vo.ConsistencyItemVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.List;

/**
 * 运行监控接口：数据质量、作业运行记录、数据一致性校验。
 */
@Tag(name = "03-运行监控", description = "数据质量、作业运行性能对比与数据一致性校验")
@RestController
@RequestMapping("/api/monitor")
public class MonitorController {

    @Resource
    private MonitorService monitorService;

    @Resource
    private AnalysisService analysisService;

    @Operation(summary = "数据质量报告", description = "最近一次离线作业的清洗统计：原始记录数、各类异常数、有效记录数与质量得分")
    @GetMapping("/quality")
    public Result<AdsCleanStat> quality() {
        return Result.success(monitorService.latestQuality());
    }

    @Operation(summary = "作业运行记录", description = "RDD 与 DataFrame 两种计算方式的耗时与输入输出行数，用于性能对比")
    @GetMapping("/job-log")
    public Result<List<EtlJobLog>> jobLog() {
        return Result.success(monitorService.jobLogs());
    }

    @Operation(summary = "数据一致性校验",
            description = "对比 ADS 预计算结果与 DWD 明细实时汇总结果，用于验证指标口径一致性")
    @GetMapping("/consistency")
    public Result<List<ConsistencyItemVO>> consistency() {
        return Result.success(analysisService.consistency());
    }
}
