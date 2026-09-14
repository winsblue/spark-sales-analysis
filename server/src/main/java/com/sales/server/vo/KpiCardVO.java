package com.sales.server.vo;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/** 核心指标卡（看板顶部展示） */
@Data
public class KpiCardVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 指标编码，如 GMV */
    private String code;

    /** 指标名称 */
    private String name;

    /** 指标值 */
    private BigDecimal value = BigDecimal.ZERO;

    /** 单位 */
    private String unit;

    /** 指标口径说明 */
    private String desc;

    /** 数据来源：PRECOMPUTE 预计算 / ONDEMAND 即席聚合 */
    private String source;
}
