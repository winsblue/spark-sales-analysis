package com.sales.sink;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 建库脚本执行器。
 *
 * <p>读取 sql/01_schema.sql，去掉注释后按分号切分并逐条执行，
 * 使整个项目可以通过一条命令完成"建库 + 生成数据 + 计算 + 落库"。</p>
 */
public final class SchemaInitializer {

    private SchemaInitializer() {
    }

    public static void init(MysqlSink sink, String sqlFilePath) {
        Path path = Paths.get(sqlFilePath).toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("建表脚本不存在: " + path);
        }
        List<String> statements = splitStatements(path);
        System.out.println("[SchemaInitializer] 开始执行建表脚本：" + path + "，共 " + statements.size() + " 条语句");
        for (String sql : statements) {
            sink.execute(sql);
        }
        System.out.println("[SchemaInitializer] 建表完成");
    }

    private static List<String> splitStatements(Path path) {
        List<String> raw;
        try {
            raw = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取建表脚本失败: " + path, e);
        }
        StringBuilder sb = new StringBuilder();
        for (String line : raw) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                continue;
            }
            sb.append(line).append('\n');
        }
        List<String> result = new ArrayList<>();
        for (String part : sb.toString().split(";")) {
            String sql = part.trim();
            if (!sql.isEmpty()) {
                result.add(sql);
            }
        }
        return result;
    }
}
