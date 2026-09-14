package com.sales.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** DWD 层：清洗后的订单明细表（明细下钻与数据导出使用） */
@Data
@TableName("dwd_order_detail")
public class DwdOrderDetail implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String orderId;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime orderTime;

    private LocalDate statDate;

    private String statMonth;

    private String statWeek;

    private String userId;

    private String productId;

    private String productName;

    private String categoryId;

    private String categoryName;

    private String brand;

    private String channel;

    private String province;

    private String city;

    private Integer quantity;

    private BigDecimal unitPrice;

    private BigDecimal discountAmount;

    private BigDecimal payAmount;

    private String orderStatus;

    private String payType;

    /** 是否有效订单：1 有效，0 取消/退款/待付款 */
    private Integer isValidOrder;

    private String batchId;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
}
