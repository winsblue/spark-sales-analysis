-- =====================================================================
--  基于 Spark 的电商商品销售离线分析与 Java 服务化应用
--  数据库结构脚本   01_schema.sql
--  目标数据库：MySQL 5.5.27（InnoDB / utf8，注意 5.5 不支持 utf8mb4）
--  分层说明：ODS（原始层） -> DWD（明细层） -> ADS（指标结果层）
--
--  设计说明：
--    ADS 层的统计结果均按"统计日期 + 分析维度"存储全量聚合值，
--    排名（TopN）与占比（Ratio）统一由后端 SQL 在查询时计算。
--    这样前端切换日期范围时，排名与占比能够随筛选条件联动，
--    而不是被固定的 TopN 截断数据所限制。
-- =====================================================================

CREATE DATABASE IF NOT EXISTS `sales_analysis` DEFAULT CHARACTER SET utf8 COLLATE utf8_general_ci;
USE `sales_analysis`;

-- =====================================================================
-- 一、ODS 层：原始数据表
--     字段与数据源 CSV 一一对应，全部按字符串落库，
--     目的是保留原始脏数据，做到"可追溯、可重跑"。
-- =====================================================================

DROP TABLE IF EXISTS `ods_order_detail`;
CREATE TABLE `ods_order_detail` (
  `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `order_id`        VARCHAR(64)           DEFAULT NULL COMMENT '订单编号',
  `order_time`      VARCHAR(32)           DEFAULT NULL COMMENT '下单时间（原始字符串，含多种格式）',
  `user_id`         VARCHAR(32)           DEFAULT NULL COMMENT '用户编号',
  `product_id`      VARCHAR(32)           DEFAULT NULL COMMENT '商品编号',
  `channel`         VARCHAR(32)           DEFAULT NULL COMMENT '销售渠道',
  `province`        VARCHAR(32)           DEFAULT NULL COMMENT '收货省份',
  `city`            VARCHAR(64)           DEFAULT NULL COMMENT '收货城市',
  `quantity`        VARCHAR(16)           DEFAULT NULL COMMENT '购买数量（原始字符串）',
  `unit_price`      VARCHAR(16)           DEFAULT NULL COMMENT '商品单价（原始字符串）',
  `discount_amount` VARCHAR(16)           DEFAULT NULL COMMENT '优惠金额（原始字符串）',
  `pay_amount`      VARCHAR(16)           DEFAULT NULL COMMENT '实付金额（原始字符串）',
  `order_status`    VARCHAR(24)           DEFAULT NULL COMMENT '订单状态',
  `pay_type`        VARCHAR(24)           DEFAULT NULL COMMENT '支付方式',
  `batch_id`        VARCHAR(40)           DEFAULT NULL COMMENT '数据批次号',
  PRIMARY KEY (`id`),
  KEY `idx_ods_order_order_id` (`order_id`),
  KEY `idx_ods_order_batch` (`batch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='ODS-订单明细原始表';

DROP TABLE IF EXISTS `ods_product`;
CREATE TABLE `ods_product` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `product_id`    VARCHAR(32)           DEFAULT NULL COMMENT '商品编号',
  `product_name`  VARCHAR(128)          DEFAULT NULL COMMENT '商品名称',
  `category_id`   VARCHAR(32)           DEFAULT NULL COMMENT '类目编号',
  `category_name` VARCHAR(64)           DEFAULT NULL COMMENT '类目名称',
  `brand`         VARCHAR(64)           DEFAULT NULL COMMENT '品牌',
  `cost_price`    VARCHAR(16)           DEFAULT NULL COMMENT '成本价',
  `launch_date`   VARCHAR(32)           DEFAULT NULL COMMENT '上架日期',
  `batch_id`      VARCHAR(40)           DEFAULT NULL COMMENT '数据批次号',
  PRIMARY KEY (`id`),
  KEY `idx_ods_product_pid` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='ODS-商品维度原始表';

-- =====================================================================
-- 二、DWD 层：清洗后的规范明细表
--     类型规整、去重、异常记录过滤、空值填充、维度关联
--     下游所有指标均由本表聚合而来
-- =====================================================================

DROP TABLE IF EXISTS `dwd_order_detail`;
CREATE TABLE `dwd_order_detail` (
  `id`              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `order_id`        VARCHAR(64)   NOT NULL COMMENT '订单编号',
  `order_time`      DATETIME      NOT NULL COMMENT '下单时间（已规整为 yyyy-MM-dd HH:mm:ss）',
  `stat_date`       DATE          NOT NULL COMMENT '下单日期（统计分区键）',
  `stat_month`      VARCHAR(7)    NOT NULL COMMENT '下单月份 yyyy-MM',
  `stat_week`       VARCHAR(12)             DEFAULT NULL COMMENT '下单周 yyyy-Www',
  `user_id`         VARCHAR(32)             DEFAULT NULL COMMENT '用户编号',
  `product_id`      VARCHAR(32)   NOT NULL COMMENT '商品编号',
  `product_name`    VARCHAR(128)            DEFAULT NULL COMMENT '商品名称（关联商品维度）',
  `category_id`     VARCHAR(32)   NOT NULL DEFAULT 'UNKNOWN' COMMENT '类目编号',
  `category_name`   VARCHAR(64)   NOT NULL DEFAULT '未知类目' COMMENT '类目名称',
  `brand`           VARCHAR(64)             DEFAULT NULL COMMENT '品牌',
  `channel`         VARCHAR(32)             DEFAULT NULL COMMENT '销售渠道',
  `province`        VARCHAR(32)   NOT NULL DEFAULT '未知' COMMENT '收货省份（空值已填充）',
  `city`            VARCHAR(64)   NOT NULL DEFAULT '未知' COMMENT '收货城市（空值已填充）',
  `quantity`        INT           NOT NULL DEFAULT 0 COMMENT '购买数量',
  `unit_price`      DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '商品单价',
  `discount_amount` DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '优惠金额',
  `pay_amount`      DECIMAL(14,2) NOT NULL DEFAULT 0.00 COMMENT '实付金额（已按公式修正）',
  `order_status`    VARCHAR(24)             DEFAULT NULL COMMENT '订单状态（标准化后）',
  `pay_type`        VARCHAR(24)   NOT NULL DEFAULT '未知' COMMENT '支付方式（空值已填充）',
  `is_valid_order`  TINYINT(1)    NOT NULL DEFAULT 1 COMMENT '是否有效订单：1=已完成/已支付，0=待付款/取消/退款',
  `batch_id`        VARCHAR(40)             DEFAULT NULL COMMENT '数据批次号',
  `create_time`     DATETIME                DEFAULT NULL COMMENT '入库时间',
  PRIMARY KEY (`id`),
  KEY `idx_dwd_date` (`stat_date`),
  KEY `idx_dwd_date_category` (`stat_date`, `category_id`),
  KEY `idx_dwd_date_channel` (`stat_date`, `channel`),
  KEY `idx_dwd_date_province` (`stat_date`, `province`),
  KEY `idx_dwd_date_paytype` (`stat_date`, `pay_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='DWD-订单明细清洗表';

DROP TABLE IF EXISTS `dwd_clean_reject`;
CREATE TABLE `dwd_clean_reject` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `batch_id`    VARCHAR(40)  NOT NULL COMMENT '数据批次号',
  `rule_code`   VARCHAR(16)  NOT NULL COMMENT '违反的清洗规则编码，如 R03',
  `rule_desc`   VARCHAR(128)          DEFAULT NULL COMMENT '规则说明',
  `order_id`    VARCHAR(64)           DEFAULT NULL COMMENT '订单编号',
  `product_id`  VARCHAR(32)           DEFAULT NULL COMMENT '商品编号',
  `raw_record`  VARCHAR(512)          DEFAULT NULL COMMENT '原始记录（截断保存，便于追溯）',
  `create_time` DATETIME              DEFAULT NULL COMMENT '记录时间',
  PRIMARY KEY (`id`),
  KEY `idx_reject_batch` (`batch_id`),
  KEY `idx_reject_rule` (`rule_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='DWD-清洗异常数据留痕表';

-- =====================================================================
-- 三、ADS 层：指标结果表
--     每张表按"统计日期 + 分析维度"存全量聚合值，供后端按条件查询
-- =====================================================================

DROP TABLE IF EXISTS `ads_overview`;
CREATE TABLE `ads_overview` (
  `id`          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `kpi_code`    VARCHAR(48)   NOT NULL COMMENT '指标编码',
  `kpi_name`    VARCHAR(64)   NOT NULL COMMENT '指标名称',
  `kpi_value`   DECIMAL(20,4) NOT NULL DEFAULT 0.0000 COMMENT '指标值',
  `kpi_unit`    VARCHAR(16)            DEFAULT NULL COMMENT '单位',
  `kpi_desc`    VARCHAR(255)           DEFAULT NULL COMMENT '指标口径说明',
  `stat_date`   DATE          NOT NULL COMMENT '统计日期（取数据区间的截止日）',
  `batch_id`    VARCHAR(40)            DEFAULT NULL COMMENT '数据批次号',
  `update_time` DATETIME               DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_overview_code_date` (`kpi_code`, `stat_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='ADS-核心指标总览表';

DROP TABLE IF EXISTS `ads_daily_trend`;
CREATE TABLE `ads_daily_trend` (
  `id`               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `stat_date`        DATE          NOT NULL COMMENT '统计日期',
  `order_cnt`        BIGINT        NOT NULL DEFAULT 0 COMMENT '下单量（去重订单数）',
  `valid_order_cnt`  BIGINT        NOT NULL DEFAULT 0 COMMENT '有效订单量',
  `gmv`              DECIMAL(20,2) NOT NULL DEFAULT 0.00 COMMENT '成交金额 GMV',
  `sales_qty`        BIGINT        NOT NULL DEFAULT 0 COMMENT '销售件数',
  `buyer_cnt`        BIGINT        NOT NULL DEFAULT 0 COMMENT '下单用户数',
  `avg_order_amount` DECIMAL(14,2) NOT NULL DEFAULT 0.00 COMMENT '客单价 = GMV / 有效订单量',
  `refund_order_cnt` BIGINT        NOT NULL DEFAULT 0 COMMENT '退款订单量',
  `refund_rate`      DECIMAL(10,4) NOT NULL DEFAULT 0.0000 COMMENT '退款率 = 退款订单量 / 下单量',
  `update_time`      DATETIME               DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_trend_date` (`stat_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='ADS-每日销售趋势表';

DROP TABLE IF EXISTS `ads_category_stat`;
CREATE TABLE `ads_category_stat` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `stat_date`     DATE          NOT NULL COMMENT '统计日期',
  `category_id`   VARCHAR(32)   NOT NULL COMMENT '类目编号',
  `category_name` VARCHAR(64)   NOT NULL COMMENT '类目名称',
  `gmv`           DECIMAL(20,2) NOT NULL DEFAULT 0.00 COMMENT '成交金额',
  `sales_qty`     BIGINT        NOT NULL DEFAULT 0 COMMENT '销售件数',
  `order_cnt`     BIGINT        NOT NULL DEFAULT 0 COMMENT '订单量（去重订单数）',
  `buyer_cnt`     BIGINT        NOT NULL DEFAULT 0 COMMENT '用户数',
  `update_time`   DATETIME               DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_category_date_id` (`stat_date`, `category_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='ADS-类目每日销售统计表';

DROP TABLE IF EXISTS `ads_product_stat`;
CREATE TABLE `ads_product_stat` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `stat_date`     DATE          NOT NULL COMMENT '统计日期',
  `product_id`    VARCHAR(32)   NOT NULL COMMENT '商品编号',
  `product_name`  VARCHAR(128)           DEFAULT NULL COMMENT '商品名称',
  `category_name` VARCHAR(64)            DEFAULT NULL COMMENT '类目名称',
  `brand`         VARCHAR(64)            DEFAULT NULL COMMENT '品牌',
  `gmv`           DECIMAL(20,2) NOT NULL DEFAULT 0.00 COMMENT '成交金额',
  `sales_qty`     BIGINT        NOT NULL DEFAULT 0 COMMENT '销售件数',
  `order_cnt`     BIGINT        NOT NULL DEFAULT 0 COMMENT '订单量（去重订单数）',
  `update_time`   DATETIME               DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_product_date_id` (`stat_date`, `product_id`),
  KEY `idx_product_date_gmv` (`stat_date`, `gmv`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='ADS-商品每日销售统计表';

DROP TABLE IF EXISTS `ads_channel_stat`;
CREATE TABLE `ads_channel_stat` (
  `id`          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `stat_date`   DATE          NOT NULL COMMENT '统计日期',
  `channel`     VARCHAR(32)   NOT NULL COMMENT '销售渠道',
  `gmv`         DECIMAL(20,2) NOT NULL DEFAULT 0.00 COMMENT '成交金额',
  `order_cnt`   BIGINT        NOT NULL DEFAULT 0 COMMENT '订单量（去重订单数）',
  `sales_qty`   BIGINT        NOT NULL DEFAULT 0 COMMENT '销售件数',
  `buyer_cnt`   BIGINT        NOT NULL DEFAULT 0 COMMENT '用户数',
  `update_time` DATETIME               DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_channel_date` (`stat_date`, `channel`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='ADS-渠道每日销售统计表';

DROP TABLE IF EXISTS `ads_paytype_stat`;
CREATE TABLE `ads_paytype_stat` (
  `id`          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `stat_date`   DATE          NOT NULL COMMENT '统计日期',
  `pay_type`    VARCHAR(24)   NOT NULL COMMENT '支付方式',
  `gmv`         DECIMAL(20,2) NOT NULL DEFAULT 0.00 COMMENT '成交金额',
  `order_cnt`   BIGINT        NOT NULL DEFAULT 0 COMMENT '订单量（去重订单数）',
  `update_time` DATETIME               DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_paytype_date` (`stat_date`, `pay_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='ADS-支付方式每日销售统计表';

DROP TABLE IF EXISTS `ads_region_stat`;
CREATE TABLE `ads_region_stat` (
  `id`          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `stat_date`   DATE          NOT NULL COMMENT '统计日期',
  `province`    VARCHAR(32)   NOT NULL COMMENT '省份',
  `gmv`         DECIMAL(20,2) NOT NULL DEFAULT 0.00 COMMENT '成交金额',
  `order_cnt`   BIGINT        NOT NULL DEFAULT 0 COMMENT '订单量（去重订单数）',
  `buyer_cnt`   BIGINT        NOT NULL DEFAULT 0 COMMENT '用户数',
  `update_time` DATETIME               DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_region_date_prov` (`stat_date`, `province`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='ADS-省份每日销售统计表';

DROP TABLE IF EXISTS `ads_channel_category_stat`;
CREATE TABLE `ads_channel_category_stat` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `stat_date`     DATE          NOT NULL COMMENT '统计日期',
  `channel`       VARCHAR(32)   NOT NULL COMMENT '销售渠道',
  `category_name` VARCHAR(64)   NOT NULL COMMENT '类目名称',
  `gmv`           DECIMAL(20,2) NOT NULL DEFAULT 0.00 COMMENT '成交金额',
  `order_cnt`     BIGINT        NOT NULL DEFAULT 0 COMMENT '订单量（去重订单数）',
  `update_time`   DATETIME               DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ch_cat_date` (`stat_date`, `channel`, `category_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='ADS-渠道与类目交叉统计表（分组对比用）';

-- =====================================================================
-- 四、运行监控层：数据质量统计 + ETL 作业运行记录
-- =====================================================================

DROP TABLE IF EXISTS `ads_clean_stat`;
CREATE TABLE `ads_clean_stat` (
  `id`                 BIGINT        NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `batch_id`           VARCHAR(40)   NOT NULL COMMENT '数据批次号',
  `run_date`           DATE          NOT NULL COMMENT '运行日期',
  `raw_cnt`            BIGINT        NOT NULL DEFAULT 0 COMMENT '原始记录数',
  `null_field_cnt`     BIGINT        NOT NULL DEFAULT 0 COMMENT '含空值的记录数（R01）',
  `dup_record_cnt`     BIGINT        NOT NULL DEFAULT 0 COMMENT '重复记录数（R02）',
  `invalid_time_cnt`   BIGINT        NOT NULL DEFAULT 0 COMMENT '时间无法解析数（R03）',
  `invalid_amount_cnt` BIGINT        NOT NULL DEFAULT 0 COMMENT '数量/单价/金额异常数（R04-R06）',
  `invalid_status_cnt` BIGINT        NOT NULL DEFAULT 0 COMMENT '订单状态非法数（R07）',
  `missing_dim_cnt`    BIGINT        NOT NULL DEFAULT 0 COMMENT '商品主数据缺失数（R08）',
  `valid_cnt`          BIGINT        NOT NULL DEFAULT 0 COMMENT '清洗后有效记录数',
  `discard_cnt`        BIGINT        NOT NULL DEFAULT 0 COMMENT '丢弃记录数',
  `quality_score`      DECIMAL(10,4) NOT NULL DEFAULT 0.0000 COMMENT '数据质量得分 = 有效记录数 / 原始记录数',
  `duration_ms`        BIGINT        NOT NULL DEFAULT 0 COMMENT '清洗耗时（毫秒）',
  `update_time`        DATETIME               DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_clean_batch` (`batch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='清洗过程数据质量统计表';

DROP TABLE IF EXISTS `etl_job_log`;
CREATE TABLE `etl_job_log` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `batch_id`     VARCHAR(40)  NOT NULL COMMENT '数据批次号',
  `job_name`     VARCHAR(64)  NOT NULL COMMENT '作业名称',
  `compute_mode` VARCHAR(24)  NOT NULL COMMENT '计算方式：RDD / DATAFRAME',
  `start_time`   DATETIME              DEFAULT NULL COMMENT '开始时间',
  `end_time`     DATETIME              DEFAULT NULL COMMENT '结束时间',
  `duration_ms`  BIGINT       NOT NULL DEFAULT 0 COMMENT '耗时（毫秒）',
  `input_rows`   BIGINT       NOT NULL DEFAULT 0 COMMENT '输入记录数',
  `output_rows`  BIGINT       NOT NULL DEFAULT 0 COMMENT '输出记录数',
  `job_status`   VARCHAR(16)           DEFAULT NULL COMMENT '执行状态：SUCCESS / FAILED / RUNNING',
  `remark`       VARCHAR(255)          DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`id`),
  KEY `idx_joblog_batch` (`batch_id`),
  KEY `idx_joblog_mode` (`compute_mode`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='ETL 作业运行记录表（用于两种计算方式性能对比）';
