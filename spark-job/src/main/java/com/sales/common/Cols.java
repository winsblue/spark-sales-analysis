package com.sales.common;

import org.apache.spark.sql.Column;

import static org.apache.spark.sql.functions.col;

/**
 * Spark 列工具：依据列名数组生成 select 参数。
 *
 * <p>把"表的写入列顺序"集中在 {@link Schemas} 维护，
 * 计算逻辑统一通过本工具按该顺序取列，避免写入错位。</p>
 */
public final class Cols {

    private Cols() {
    }

    public static Column[] of(String[] names) {
        Column[] cols = new Column[names.length];
        for (int i = 0; i < names.length; i++) {
            cols[i] = col(names[i]);
        }
        return cols;
    }
}
