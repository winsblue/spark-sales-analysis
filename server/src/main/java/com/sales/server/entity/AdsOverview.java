package com.sales.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** ADS 层：核心指标总览表 */
@Data
@TableName("ads_overview")
public class AdsOverview implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 指标编码 */
    private String kpiCode;

    /** 指标名称 */
    private String kpiName;

    /** 指标值 */
    private BigDecimal kpiValue;

    /** 单位 */
    private String kpiUnit;

    /** 指标口径说明 */
    private String kpiDesc;

    /** 统计日期 */
    private LocalDate statDate;

    /** 数据批次号 */
    private String batchId;

    private LocalDateTime updateTime;
}
