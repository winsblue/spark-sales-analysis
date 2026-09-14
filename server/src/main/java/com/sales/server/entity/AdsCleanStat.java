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

/** 清洗过程数据质量统计表 */
@Data
@TableName("ads_clean_stat")
public class AdsCleanStat implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String batchId;

    private LocalDate runDate;

    /** 原始记录数 */
    private Long rawCnt;

    /** R01 含空值记录数 */
    private Long nullFieldCnt;

    /** R02 重复记录数 */
    private Long dupRecordCnt;

    /** R03 时间异常数 */
    private Long invalidTimeCnt;

    /** R04-R06 数量/单价/金额异常数 */
    private Long invalidAmountCnt;

    /** R07 状态非法数 */
    private Long invalidStatusCnt;

    /** R08 商品主数据缺失数 */
    private Long missingDimCnt;

    /** 清洗后有效记录数 */
    private Long validCnt;

    /** 丢弃记录数 */
    private Long discardCnt;

    /** 数据质量得分 */
    private BigDecimal qualityScore;

    /** 清洗耗时（毫秒） */
    private Long durationMs;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;
}
