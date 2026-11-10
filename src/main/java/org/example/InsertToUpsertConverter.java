package org.example;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * 将INSERT INTO语句转换为MySQL的 INSERT ... ON DUPLICATE KEY UPDATE 语句。
 * 主键/唯一键映射关系从 table_pk_config.properties 配置文件读取。
 * 路径配置从 upsert_config.properties 读取。
 */
public class InsertToUpsertConverter {

    // 匹配多值INSERT: INSERT INTO `table` (cols) VALUES (v1),(v2),...;
    private static final Pattern INSERT_PATTERN = Pattern.compile(
            "INSERT\\s+INTO\\s+`?([\\w-]+)`?\\s*\\((.+?)\\)\\s*VALUES\\s+(.+?)\\s*;\\s*$",
            Pattern.CASE_INSENSITIVE);

    /** INSERT SQL输入目录 */
    private final String sqlInputDir;
    /** UPSERT SQL输出目录 */
    private final String sqlOutputDir;
    /** 表名 -> 主键列集合 (大写) */
    private final Map<String, Set<String>> tablePkMap;

    public InsertToUpsertConverter(String sqlInputDir, String sqlOutputDir, Map<String, Set<String>> tablePkMap) {
        this.sqlInputDir = sqlInputDir;
        this.sqlOutputDir = sqlOutputDir;
        this.tablePkMap = tablePkMap;
    }

    public static void main(String[] args) throws Exception {
        // 加载配置
        Properties props = loadProperties("application.properties");
        String sqlInputDir = props.getProperty("upsert.input.dir");
        String sqlOutputDir = props.getProperty("upsert.output.dir");

        // 加载主键配置
        Properties pkProps = loadProperties("table_pk_config.properties");
        Map<String, Set<String>> tablePkMap = parsePkConfig(pkProps);

        InsertToUpsertConverter converter = new InsertToUpsertConverter(sqlInputDir, sqlOutputDir, tablePkMap);
        converter.convert();
    }

    /**
     * 主流程：遍历输入目录下所有.sql文件，逐个转换
     */
    public void convert() throws Exception {
        Files.createDirectories(Paths.get(sqlOutputDir));

        File inputFolder = new File(sqlInputDir);
        File[] sqlFiles = inputFolder.listFiles((dir, name) -> name.toLowerCase().endsWith(".sql"));
        if (sqlFiles == null || sqlFiles.length == 0) {
            System.out.println("输入目录下未找到任何.sql文件: " + sqlInputDir);
            return;
        }

        for (File sqlFile : sqlFiles) {
            processSqlFile(sqlFile);
        }

        System.out.println("UPSERT SQL生成完毕，输出目录: " + sqlOutputDir);
    }

    /**
     * 处理单个SQL文件
     */
    private void processSqlFile(File sqlFile) throws Exception {
        File outputFile = new File(sqlOutputDir, sqlFile.getName());
        long count = 0;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(sqlFile), StandardCharsets.UTF_8), 256 * 1024);
             BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(outputFile), StandardCharsets.UTF_8), 256 * 1024)) {

            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                String upsertSql = convertToUpsert(trimmed);
                writer.write(upsertSql);
                writer.newLine();
                count++;
            }
        }

        System.out.println("已生成: " + outputFile.getName() + " (" + count + "条UPSERT语句)");
    }

    /**
     * 将INSERT语句转换为INSERT ... ON DUPLICATE KEY UPDATE语句
     * 支持单值和多值INSERT格式
     * 如果表未配置主键，则原样保留
     */
    private String convertToUpsert(String insertSql) {
        Matcher matcher = INSERT_PATTERN.matcher(insertSql);

        if (!matcher.matches()) {
            // 无法解析，原样返回
            return insertSql;
        }

        String tableName = matcher.group(1);
        String columnsPart = matcher.group(2);
        String valuesAll = matcher.group(3);

        // 查找该表的主键配置（不区分大小写）
        Set<String> pkColumns = tablePkMap.get(tableName.toUpperCase());
        if (pkColumns == null || pkColumns.isEmpty()) {
            // 未配置主键，原样返回
            System.out.println("  警告: 表 " + tableName + " 未配置主键，原样保留INSERT语句");
            return insertSql;
        }

        // 解析列名列表
        String[] columns = splitColumns(columnsPart);

        // 轻量验证：取VALUES中第一个值组校验列数
        String firstGroup = extractFirstValueGroup(valuesAll);
        if (firstGroup == null) {
            return insertSql;
        }
        String[] firstValues = splitValues(firstGroup);
        if (columns.length != firstValues.length) {
            // 列数和值数不匹配，原样返回
            System.out.println("  警告: 表 " + tableName + " 列数与值数不匹配，原样保留");
            return insertSql;
        }

        // 构建ON DUPLICATE KEY UPDATE子句（排除主键列）
        StringBuilder updateClause = new StringBuilder();
        boolean first = true;
        for (int i = 0; i < columns.length; i++) {
            String colName = columns[i].trim();
            if (pkColumns.contains(colName.toUpperCase())) {
                continue; // 跳过主键列
            }
            if (!first) {
                updateClause.append(", ");
            }
            updateClause.append(colName).append(" = VALUES(").append(colName).append(")");
            first = false;
        }

        // 如果所有列都是主键列（无需更新的列），使用INSERT IGNORE
        if (updateClause.length() == 0) {
            return "INSERT IGNORE INTO `" + tableName + "` (" + columnsPart + ") VALUES " + valuesAll + ";";
        }

        // 构建完整的UPSERT语句
        StringBuilder sb = new StringBuilder();
        sb.append("INSERT INTO `").append(tableName)
          .append("` (").append(columnsPart).append(")")
          .append(" VALUES ").append(valuesAll)
          .append(" ON DUPLICATE KEY UPDATE ")
          .append(updateClause)
          .append(";");

        return sb.toString();
    }

    /**
     * 提取多值INSERT中第一个值组的内容（仅扫描到第一个组即停止，轻量操作）
     * 例如: ('val1','val2'),('val3','val4') -> "'val1','val2'"
     */
    private String extractFirstValueGroup(String valuesAll) {
        int depth = 0;
        int start = -1;
        boolean inQuote = false;

        for (int i = 0; i < valuesAll.length(); i++) {
            char c = valuesAll.charAt(i);
            if (c == '\'' && !inQuote) {
                inQuote = true;
            } else if (c == '\'' && inQuote) {
                if (i + 1 < valuesAll.length() && valuesAll.charAt(i + 1) == '\'') {
                    i++;
                } else {
                    inQuote = false;
                }
            } else if (c == '(' && !inQuote) {
                if (depth == 0) {
                    start = i + 1;
                }
                depth++;
            } else if (c == ')' && !inQuote) {
                depth--;
                if (depth == 0 && start >= 0) {
                    return valuesAll.substring(start, i);
                }
            }
        }

        return null;
    }

    /**
     * 分割列名（按逗号分割，去除空格）
     */
    private String[] splitColumns(String columnsPart) {
        return columnsPart.split("\\s*,\\s*");
    }

    /**
     * 分割VALUES中的值（需要处理字符串中包含逗号的情况）
     * 例如: 'hello, world', 'test' 应分割为两个值
     */
    private String[] splitValues(String valuesPart) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;
        int parenDepth = 0;

        for (int i = 0; i < valuesPart.length(); i++) {
            char c = valuesPart.charAt(i);

            if (c == '\'' && !inQuote) {
                inQuote = true;
                current.append(c);
            } else if (c == '\'' && inQuote) {
                // 检查是否是转义的单引号 ''
                if (i + 1 < valuesPart.length() && valuesPart.charAt(i + 1) == '\'') {
                    current.append("''");
                    i++; // 跳过下一个引号
                } else {
                    inQuote = false;
                    current.append(c);
                }
            } else if (c == '(' && !inQuote) {
                parenDepth++;
                current.append(c);
            } else if (c == ')' && !inQuote) {
                parenDepth--;
                current.append(c);
            } else if (c == ',' && !inQuote && parenDepth == 0) {
                values.add(current.toString().trim());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        // 最后一个值
        if (current.length() > 0) {
            values.add(current.toString().trim());
        }

        return values.toArray(new String[0]);
    }

    /**
     * 加载classpath下的properties文件
     */
    private static Properties loadProperties(String fileName) throws Exception {
        Properties props = new Properties();
        try (InputStream is = InsertToUpsertConverter.class.getClassLoader().getResourceAsStream(fileName)) {
            if (is == null) {
                throw new FileNotFoundException("找不到配置文件: " + fileName);
            }
            props.load(new InputStreamReader(is, StandardCharsets.UTF_8));
        }
        return props;
    }

    /**
     * 解析主键配置：表名(大写) -> 主键列集合(大写)
     */
    private static Map<String, Set<String>> parsePkConfig(Properties pkProps) {
        Map<String, Set<String>> map = new HashMap<>();
        for (String tableName : pkProps.stringPropertyNames()) {
            String pkValue = pkProps.getProperty(tableName);
            if (pkValue == null || pkValue.trim().isEmpty()) {
                continue;
            }
            Set<String> pkSet = new LinkedHashSet<>();
            for (String pk : pkValue.split(",")) {
                pkSet.add(pk.trim().toUpperCase());
            }
            map.put(tableName.toUpperCase(), pkSet);
        }
        return map;
    }
}
