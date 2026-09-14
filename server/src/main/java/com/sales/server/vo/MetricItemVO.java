package com.sales.server.vo;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 通用指标项：用于类目 / 渠道 / 支付方式 / 省份的排行与占比展示。
 */
@Data
public class MetricItemVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 维度名称，如类目名、渠道名、省份名 */
    private String name;

    /** 成交金额 */
    private BigDecimal gmv = BigDecimal.ZERO;

    /** 销售件数 */
    private Long salesQty = 0L;

    /** 订单量 */
    private Long orderCnt = 0L;

    /** 用户数 */
    private Long buyerCnt = 0L;

    /** 占比（百分数，保留 2 位小数） */
    private BigDecimal ratio = BigDecimal.ZERO;

    /** 排名（从 1 开始） */
    private Integer rankNo;
}
