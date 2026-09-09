package com.sales.sink;

import org.apache.spark.sql.Row;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.List;

/**
 * MySQL 写入工具。
 *
 * <p>统一封装连接获取、DDL 执行、批量插入，
 * 供 DataFrame 与 RDD 两种计算方式共用，保证写入逻辑一致。</p>
 */
public class MysqlSink {

    private static final int BATCH_SIZE = 1000;

    private final String url;
    private final String user;
    private final String password;

    public MysqlSink(String url, String user, String password) {
        this.url = buildUrl(url);
        this.user = user;
        this.password = password;
        loadDriver();
    }

    /** 补充批量插入优化参数：开启 rewriteBatchedStatements 可显著提升批量写入性能 */
    private static String buildUrl(String rawUrl) {
        if (rawUrl.contains("rewriteBatchedStatements")) {
            return rawUrl;
        }
        return rawUrl + (rawUrl.contains("?") ? "&" : "?") + "rewriteBatchedStatements=true";
    }

    /**
     * 从连接串中解析出数据库名，例如
     * {@code jdbc:mysql://127.0.0.1:3306/sales_analysis?xxx} -> {@code sales_analysis}
     */
    public static String extractDatabase(String url) {
        int q = url.indexOf('?');
        String base = q >= 0 ? url.substring(0, q) : url;
        int slash = base.lastIndexOf('/');
        return slash >= 0 ? base.substring(slash + 1) : "";
    }

    /** 生成不带数据库名的服务级连接串 */
    public static String serverUrl(String url) {
        int q = url.indexOf('?');
        String base = q >= 0 ? url.substring(0, q) : url;
        String params = q >= 0 ? url.substring(q) : "";
        int slash = base.lastIndexOf('/');
        int hostEnd = base.indexOf("//");
        if (slash > hostEnd + 2) {
            base = base.substring(0, slash);
        }
        return base + params;
    }

    /**
     * 确保目标数据库存在。
     *
     * <p>首次运行时数据库尚未创建，若直接使用带库名的连接串会连接失败，
     * 因此这里先用"服务级连接串"创建数据库。</p>
     */
    public static void ensureDatabase(String url, String user, String password) {
        String db = extractDatabase(url);
        if (db == null || db.isEmpty()) {
            return;
        }
        try (Connection c = DriverManager.getConnection(serverUrl(url), user, password);
             Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE IF NOT EXISTS `" + db
                    + "` DEFAULT CHARACTER SET utf8 COLLATE utf8_general_ci");
        } catch (SQLException e) {
            throw new IllegalStateException("创建数据库失败: " + db + " -> " + e.getMessage(), e);
        }
    }

    private static void loadDriver() {
        try {
            Class.forName("com.mysql.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            try {
                Class.forName("com.mysql.cj.jdbc.Driver");
            } catch (ClassNotFoundException ex) {
                throw new IllegalStateException("未找到 MySQL JDBC 驱动，请检查 mysql-connector-java 依赖", ex);
            }
        }
    }

    public Connection open() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    /** 连通性检查，作业启动前调用 */
    public void ping() {
        try (Connection c = open()) {
            if (!c.isValid(5)) {
                throw new SQLException("连接无效");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("无法连接结果库，请确认 MySQL 已启动且库 sales_analysis 已初始化。当前地址："
                    + url + "，原因：" + e.getMessage(), e);
        }
    }

    public void execute(String sql) {
        try (Connection c = open(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("执行 SQL 失败: " + sql + " -> " + e.getMessage(), e);
        }
    }

    /** 清空目标表，保证作业可重复运行（幂等） */
    public void truncate(String... tables) {
        try (Connection c = open(); Statement st = c.createStatement()) {
            for (String t : tables) {
                st.execute("TRUNCATE TABLE " + t);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("清空结果表失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将 Spark 结果行批量写入指定表。
     *
     * @param table   目标表名
     * @param columns 写入列（顺序必须与该表结构一致）
     * @param rows    数据行，取值顺序必须与 columns 一致
     * @return 写入行数
     */
    public long insertRows(String table, String[] columns, List<Row> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        String sql = buildInsertSql(table, columns);
        long total = 0;
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            c.setAutoCommit(false);
            int pending = 0;
            for (Row row : rows) {
                for (int i = 0; i < columns.length; i++) {
                    ps.setObject(i + 1, normalize(row.get(i)));
                }
                ps.addBatch();
                pending++;
                if (pending % BATCH_SIZE == 0) {
                    total += sum(ps.executeBatch());
                    c.commit();
                }
            }
            if (pending % BATCH_SIZE != 0) {
                total += sum(ps.executeBatch());
            }
            c.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("写入表 " + table + " 失败: " + e.getMessage(), e);
        }
        return total;
    }

    /** 通用的多行插入（供少量元数据写入使用） */
    public long insertObjects(String table, String[] columns, List<Object[]> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        String sql = buildInsertSql(table, columns);
        long total = 0;
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            c.setAutoCommit(false);
            int pending = 0;
            for (Object[] row : rows) {
                for (int i = 0; i < columns.length; i++) {
                    ps.setObject(i + 1, normalize(row[i]));
                }
                ps.addBatch();
                pending++;
                if (pending % BATCH_SIZE == 0) {
                    total += sum(ps.executeBatch());
                    c.commit();
                }
            }
            if (pending % BATCH_SIZE != 0) {
                total += sum(ps.executeBatch());
            }
            c.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("写入表 " + table + " 失败: " + e.getMessage(), e);
        }
        return total;
    }

    private static String buildInsertSql(String table, String[] columns) {
        StringBuilder sb = new StringBuilder("INSERT INTO ").append(table).append(" (");
        StringBuilder qs = new StringBuilder();
        for (int i = 0; i < columns.length; i++) {
            if (i > 0) {
                sb.append(", ");
                qs.append(", ");
            }
            sb.append(columns[i]);
            qs.append('?');
        }
        sb.append(") VALUES (").append(qs).append(')');
        return sb.toString();
    }

    /** Spark Row 的取值类型转换：统一成 JDBC 可识别的对象 */
    private static Object normalize(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Integer) {
            return v;
        }
        if (v instanceof java.sql.Date || v instanceof java.sql.Timestamp) {
            return v;
        }
        if (v instanceof java.time.LocalDate) {
            return java.sql.Date.valueOf((java.time.LocalDate) v);
        }
        if (v instanceof java.time.LocalDateTime) {
            return java.sql.Timestamp.valueOf((java.time.LocalDateTime) v);
        }
        if (v instanceof BigDecimal) {
            return v;
        }
        if (v instanceof Double || v instanceof Float) {
            return BigDecimal.valueOf(((Number) v).doubleValue());
        }
        if (v instanceof Long || v instanceof Short || v instanceof Byte || v instanceof Boolean) {
            return v;
        }
        return String.valueOf(v);
    }

    private static long sum(int[] results) {
        return countBatch(results);
    }

    /**
     * 统计批量执行影响的行数。
     *
     * <p>注意：开启 rewriteBatchedStatements 后，MySQL 驱动对批量中的每条语句
     * 通常返回 {@link Statement#SUCCESS_NO_INFO}（-2）而不是实际行数，
     * 因此这里统计"执行成功的语句数量"，而不能简单地把返回值相加。</p>
     */
    private static long countBatch(int[] results) {
        long n = 0;
        for (int r : results) {
            if (r >= 0 || r == Statement.SUCCESS_NO_INFO) {
                n++;
            }
        }
        return n;
    }

    /** 打印 SQL 警告，便于排查字符集等隐性问题 */
    public static void logWarnings(Connection c) throws SQLException {
        SQLWarning w = c.getWarnings();
        while (w != null) {
            System.err.println("[MysqlSink][WARN] " + w.getMessage());
            w = w.getNextWarning();
        }
    }
}
