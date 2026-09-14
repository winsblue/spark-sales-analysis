package com.sales.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** ADS 层：每日销售趋势表 */
@Data
@TableName("ads_daily_trend")
public class AdsDailyTrend implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private LocalDate statDate;

    /** 下单量（去重订单数） */
    private Long orderCnt;

    /** 有效订单量 */
    private Long validOrderCnt;

    /** 成交金额 */
    private BigDecimal gmv;

    /** 销售件数 */
    private Long salesQty;

    /** 下单用户数 */
    private Long buyerCnt;

    /** 客单价 */
    private BigDecimal avgOrderAmount;

    /** 退款订单量 */
    private Long refundOrderCnt;

    /** 退款率 */
    private BigDecimal refundRate;

    private LocalDateTime updateTime;
}
