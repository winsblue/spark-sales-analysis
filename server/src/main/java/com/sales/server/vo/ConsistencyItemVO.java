package com.sales.server.vo;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 数据一致性校验项。
 *
 * <p>用于验证"指标结果表（ADS）"与"明细表（DWD）实时汇总"是否一致，
 * 是数据正确性测试的重要手段。</p>
 */
@Data
public class ConsistencyItemVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 校验项名称 */
    private String item;

    /** 指标结果表中的值 */
    private BigDecimal adsValue = BigDecimal.ZERO;

    /** 明细表实时汇总值 */
    private BigDecimal detailValue = BigDecimal.ZERO;

    /** 差值 */
    private BigDecimal diff = BigDecimal.ZERO;

    /** 单位 */
    private String unit;

    /** 是否通过 */
    private Boolean passed = Boolean.TRUE;
}
