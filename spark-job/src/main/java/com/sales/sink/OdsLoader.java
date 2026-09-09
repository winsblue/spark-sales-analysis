package com.sales.sink;

import com.sales.common.Cols;
import com.sales.common.Schemas;
import com.sales.config.JobConfig;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;

import java.util.Properties;

import static org.apache.spark.sql.functions.lit;

/**
 * ODS 层数据装载。
 *
 * <p>把数据源 CSV 原样写入数据库的 ODS 表，模拟生产环境中
 * "数据采集 -> 数据导入（Sqoop/DataX）" 的落库环节。</p>
 *
 * <p>由于数据量较大（约 20 万行），这里使用 Spark 自带的 JDBC 写入能力，
 * 通过分批提交避免一次性把数据拉到 Driver 造成内存压力。</p>
 */
public final class OdsLoader {

    private OdsLoader() {
    }

    public static void loadOrders(SparkSession spark, JobConfig config, String batchId) {
        Dataset<Row> orders = spark.read()
                .option("header", "true")
                .option("encoding", "UTF-8")
                .option("mode", "PERMISSIVE")
                .schema(Schemas.orderRawSchema())
                .csv(config.orderRawFile())
                .withColumn("batch_id", lit(batchId))
                .select(Cols.of(Schemas.ODS_ORDER_COLUMNS));

        long rows = orders.count();
        writeJdbc(config, orders, "ods_order_detail");
        System.out.println("[OdsLoader] ods_order_detail 装载 " + rows + " 行");
    }

    public static void loadProducts(SparkSession spark, JobConfig config, String batchId) {
        Dataset<Row> products = spark.read()
                .option("header", "true")
                .option("encoding", "UTF-8")
                .option("mode", "PERMISSIVE")
                .schema(Schemas.productRawSchema())
                .csv(config.productRawFile())
                .withColumn("batch_id", lit(batchId))
                .select(Cols.of(Schemas.ODS_PRODUCT_COLUMNS));

        long rows = products.count();
        writeJdbc(config, products, "ods_product");
        System.out.println("[OdsLoader] ods_product 装载 " + rows + " 行");
    }

    /** 写入 DWD 明细表（同样数据量较大，使用 Spark JDBC 写入） */
    public static void writeDwd(SparkSession spark, JobConfig config, Dataset<Row> dwd) {
        writeJdbc(config, dwd.select(Cols.of(Schemas.DWD_COLUMNS)), "dwd_order_detail");
    }

    private static void writeJdbc(JobConfig config, Dataset<Row> df, String table) {
        df.write()
                .mode("append")
                .option("batchsize", 2000)
                .option("isolationLevel", "READ_COMMITTED")
                .jdbc(config.jdbcUrl(), table, jdbcProperties(config));
    }

    public static Properties jdbcProperties(JobConfig config) {
        Properties props = new Properties();
        props.setProperty("user", config.jdbcUser());
        props.setProperty("password", config.jdbcPassword());
        // MySQL 5.5 使用 5.1.x 驱动
        props.setProperty("driver", "com.mysql.jdbc.Driver");
        return props;
    }
}
