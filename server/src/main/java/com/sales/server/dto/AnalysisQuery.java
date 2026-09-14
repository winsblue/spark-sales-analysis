package com.sales.server.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 多维度查询条件。
 *
 * <p>看板与明细页共用同一套筛选条件，前端切换条件时所有图表同步刷新
 * （即提高层要求的"图表联动"）。</p>
 */
@Data
public class AnalysisQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 开始日期，格式 yyyy-MM-dd，为空则取数据最小日期 */
    private String startDate;

    /** 结束日期，格式 yyyy-MM-dd，为空则取数据最大日期 */
    private String endDate;

    /** 销售渠道 */
    private String channel;

    /** 类目名称 */
    private String categoryName;

    /** 省份 */
    private String province;

    /** 支付方式 */
    private String payType;

    /** 订单状态（仅明细查询使用） */
    private String orderStatus;

    /** 是否仅统计有效订单：1 是（默认），0 否 */
    private Integer onlyValid = 1;

    /** 排行 / TopN 取前多少条，默认 10 */
    private Integer limit = 10;

    /** 页码，从 1 开始 */
    private Integer pageNum = 1;

    /** 每页条数 */
    private Integer pageSize = 20;

    /** 明细导出上限，防止一次导出过多数据 */
    private Integer exportLimit = 5000;

    public int limitOrDefault() {
        if (limit == null || limit <= 0) {
            return 10;
        }
        return Math.min(limit, 100);
    }

    public int offset() {
        int p = pageNum == null || pageNum < 1 ? 1 : pageNum;
        return (p - 1) * pageSizeOrDefault();
    }

    public int pageSizeOrDefault() {
        if (pageSize == null || pageSize <= 0) {
            return 20;
        }
        return Math.min(pageSize, 200);
    }

    public int exportLimitOrDefault() {
        if (exportLimit == null || exportLimit <= 0) {
            return 5000;
        }
        return Math.min(exportLimit, 50000);
    }

    /** 空白字符串统一转 null，避免 SQL 里出现 name = '' 造成查不到数据 */
    public void normalize() {
        startDate = blankToNull(startDate);
        endDate = blankToNull(endDate);
        channel = blankToNull(channel);
        categoryName = blankToNull(categoryName);
        province = blankToNull(province);
        payType = blankToNull(payType);
        orderStatus = blankToNull(orderStatus);
        if (startDate != null && endDate != null && startDate.compareTo(endDate) > 0) {
            String tmp = startDate;
            startDate = endDate;
            endDate = tmp;
        }
    }

    private static String blankToNull(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }
}
