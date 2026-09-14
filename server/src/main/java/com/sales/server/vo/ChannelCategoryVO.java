package com.sales.server.vo;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/** 渠道 × 类目 交叉对比项（分组对比分析） */
@Data
public class ChannelCategoryVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String channel;

    private String categoryName;

    private BigDecimal gmv = BigDecimal.ZERO;

    private Long orderCnt = 0L;
}
