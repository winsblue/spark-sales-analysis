package com.sales.server.vo;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/** 趋势曲线上的一个数据点（按天） */
@Data
public class TrendPointVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private LocalDate statDate;

    /** 下单量（去重订单数） */
    private Long orderCnt = 0L;

    /** 有效订单量 */
    private Long validOrderCnt = 0L;

    private BigDecimal gmv = BigDecimal.ZERO;

    private Long salesQty = 0L;

    private Long buyerCnt = 0L;

    private BigDecimal avgOrderAmount = BigDecimal.ZERO;

    private Long refundOrderCnt = 0L;

    private BigDecimal refundRate = BigDecimal.ZERO;
}
