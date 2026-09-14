package com.sales.server.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 筛选条件候选项。
 *
 * <p>前端页面初始化时调用，用于渲染日期范围、渠道、类目、省份等下拉框，
 * 避免把维度取值硬编码在前端。</p>
 */
@Data
public class FilterMetaVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 数据最小日期 */
    private String minDate;

    /** 数据最大日期 */
    private String maxDate;

    private List<String> channels = new ArrayList<>();

    private List<String> categories = new ArrayList<>();

    private List<String> provinces = new ArrayList<>();

    private List<String> payTypes = new ArrayList<>();
}
