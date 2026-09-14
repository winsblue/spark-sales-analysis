package com.sales.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** ETL 作业运行记录表（用于两种计算方式的性能对比展示） */
@Data
@TableName("etl_job_log")
public class EtlJobLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String batchId;

    /** 作业名称 */
    private String jobName;

    /** 计算方式：DATAFRAME / RDD */
    private String computeMode;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;

    /** 总耗时（毫秒） */
    private Long durationMs;

    /** 输入记录数 */
    private Long inputRows;

    /** 输出记录数 */
    private Long outputRows;

    /** 执行状态 */
    private String jobStatus;

    private String remark;
}
