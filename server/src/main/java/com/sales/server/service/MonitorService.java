package com.sales.server.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sales.server.entity.AdsCleanStat;
import com.sales.server.entity.EtlJobLog;
import com.sales.server.mapper.AdsCleanStatMapper;
import com.sales.server.mapper.EtlJobLogMapper;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;

/**
 * 运行监控服务：数据质量报告与 ETL 作业运行记录。
 */
@Service
public class MonitorService {

    @Resource
    private AdsCleanStatMapper cleanStatMapper;

    @Resource
    private EtlJobLogMapper jobLogMapper;

    /** 最近一次离线作业的数据质量统计 */
    public AdsCleanStat latestQuality() {
        List<AdsCleanStat> list = cleanStatMapper.selectList(
                Wrappers.<AdsCleanStat>lambdaQuery().orderByDesc(AdsCleanStat::getId).last("LIMIT 1"));
        return (list == null || list.isEmpty()) ? null : list.get(0);
    }

    /** 全部作业运行记录，用于展示 RDD 与 DataFrame 两种计算方式的性能对比 */
    public List<EtlJobLog> jobLogs() {
        List<EtlJobLog> list = jobLogMapper.selectList(
                Wrappers.<EtlJobLog>lambdaQuery().orderByAsc(EtlJobLog::getId));
        return list == null ? Collections.emptyList() : list;
    }
}
