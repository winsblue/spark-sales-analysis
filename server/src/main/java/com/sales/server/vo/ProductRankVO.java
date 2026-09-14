package com.sales.server.vo;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/** 商品销售排行项 */
@Data
public class ProductRankVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer rankNo;

    private String productId;

    private String productName;

    private String categoryName;

    private String brand;

    private BigDecimal gmv = BigDecimal.ZERO;

    private Long salesQty = 0L;

    private Long orderCnt = 0L;
}
