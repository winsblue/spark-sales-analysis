package com.sales.sink;

import com.sales.common.Cols;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;

import java.util.List;

/**
 * 结果表写入器。
 *
 * <p>DataFrame 与 RDD 两种计算方式共用同一套写入逻辑，
 * 保证两种方式产出的数据结构完全一致，性能对比才有可比性。</p>
 */
public final class TableWriter {

    private TableWriter() {
    }

    /** 写入 DataFrame 结果：先按列顺序重排，再批量落库 */
    public static long write(MysqlSink sink, Dataset<Row> df, String table, String[] columns) {
        Dataset<Row> ordered = df.select(Cols.of(columns));
        List<Row> rows = ordered.collectAsList();
        long n = sink.insertRows(table, columns, rows);
        System.out.println("[TableWriter] " + table + " 写入 " + n + " 行");
        return n;
    }

    /** 写入 RDD 结果：行内字段顺序需与 columns 保持一致 */
    public static long write(MysqlSink sink, JavaRDD<Row> rdd, String table, String[] columns) {
        List<Row> rows = rdd.collect();
        long n = sink.insertRows(table, columns, rows);
        System.out.println("[TableWriter] " + table + " 写入 " + n + " 行");
        return n;
    }
}
