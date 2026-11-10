package org.example;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 读取CTL定义文件和DAT数据文件，生成INSERT INTO的SQL文件。
 * CTL文件定义了表名和各列的固定宽度位置信息，DAT文件为定宽格式的数据文件。
 * 每张表生成一个独立的SQL文件，输出到配置的目录下。
 */
public class CtlToSqlGenerator {

    // 匹配表名: TRUNCATE INTO TABLE xxx 或 INSERT INTO TABLE xxx 或 APPEND INTO TABLE xxx
    private static final Pattern TABLE_PATTERN = Pattern.compile(
            "(?:TRUNCATE|INSERT|APPEND|REPLACE)\\s+INTO\\s+TABLE\\s+([\\w-]+)",
            Pattern.CASE_INSENSITIVE);

    // 匹配列定义: COLUMN_NAME POSITION(start:end)
    private static final Pattern COL_PATTERN = Pattern.compile(
            "^\\s*(\\w+)\\s+POSITION\\s*\\(\\s*(\\d+)\\s*:\\s*(\\d+)\\s*\\)",
            Pattern.CASE_INSENSITIVE);

    private CtlToSqlGenerator() {
    }

    /**
     * 生成INSERT SQL文件
     *
     * @param ctlDir       CTL文件目录
     * @param datDir       DAT文件目录
     * @param sqlOutputDir SQL输出目录
     * @param encoding     DAT文件编码（如 "UTF-8"、"GBK"）
     */
    public static void generate(String ctlDir, String datDir, String sqlOutputDir, String encoding, int batchSize) throws Exception {
        Charset datCharset = Charset.forName(encoding);

        // 确保输出目录存在
        Files.createDirectories(Paths.get(sqlOutputDir));

        File ctlFolder = new File(ctlDir);
        File[] ctlFiles = ctlFolder.listFiles((dir, name) -> name.toLowerCase().endsWith(".ctl"));
        if (ctlFiles == null || ctlFiles.length == 0) {
            System.out.println("CTL目录下未找到任何.ctl文件: " + ctlDir);
            return;
        }

        for (File ctlFile : ctlFiles) {
            processCtlFile(ctlFile, datDir, sqlOutputDir, datCharset, batchSize);
        }

        System.out.println("SQL生成完毕，输出目录: " + sqlOutputDir);
    }

    public static void main(String[] args) throws Exception {
        // 加载配置文件
        Properties props = new Properties();
        try (InputStream is = CtlToSqlGenerator.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (is == null) {
                throw new FileNotFoundException("找不到配置文件 application.properties");
            }
            props.load(new InputStreamReader(is, StandardCharsets.UTF_8));
        }

        String ctlDir = props.getProperty("ctl.dir");
        String datDir = props.getProperty("dat.dir");
        String sqlOutputDir = props.getProperty("dat.sql.output.dir");
        String encoding = props.getProperty("dat.file.encoding", "UTF-8");
        int batchSize = Integer.parseInt(props.getProperty("batch.commit.size", "1000"));

        generate(ctlDir, datDir, sqlOutputDir, encoding, batchSize);
    }

    /**
     * 处理单个CTL文件：解析表结构，读取对应DAT文件，生成SQL
     */
    private static void processCtlFile(File ctlFile, String datDir, String sqlOutputDir, Charset datCharset, int batchSize) throws Exception {
        String fileName = ctlFile.getName();
        String baseName = fileName.substring(0, fileName.lastIndexOf('.'));

        // 解析CTL文件获取表名和列定义
        CtlDefinition ctlDef = parseCtlFile(ctlFile);
        if (ctlDef == null) {
            System.out.println("跳过无法解析的CTL文件: " + fileName);
            return;
        }

        // 查找对应的DAT文件
        File datFile = new File(datDir, baseName + ".dat");
        if (!datFile.exists()) {
            System.out.println("未找到对应的DAT文件: " + datFile.getAbsolutePath());
            return;
        }

        // 生成SQL文件
        File sqlFile = new File(sqlOutputDir, baseName + ".sql");
        long totalCount = 0;

        try (ByteLineReader reader = new ByteLineReader(datFile.toPath());
             BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(sqlFile), StandardCharsets.UTF_8), 256 * 1024)) {

            String header = buildColumnHeader(ctlDef);
            List<String> valueTuples = new ArrayList<>(batchSize);

            byte[] lineBytes;
            while ((lineBytes = reader.readLine()) != null) {
                if (lineBytes.length == 0) {
                    continue;
                }
                String valueTuple = buildValueTuple(ctlDef, lineBytes, datCharset);
                if (valueTuple != null) {
                    valueTuples.add(valueTuple);
                    totalCount++;

                    if (valueTuples.size() >= batchSize) {
                        flushBatch(writer, header, valueTuples);
                        valueTuples.clear();
                    }
                }
            }

            // 刷出剩余数据
            if (!valueTuples.isEmpty()) {
                flushBatch(writer, header, valueTuples);
            }
        }

        System.out.println("已生成: " + sqlFile.getName() + " (" + totalCount + "行数据)");
    }

    /**
     * 解析CTL文件，提取表名和列定义（列名+起止位置）
     */
    private static CtlDefinition parseCtlFile(File ctlFile) throws Exception {
        List<String> lines = Files.readAllLines(ctlFile.toPath(), StandardCharsets.UTF_8);

        String tableName = null;
        List<ColumnDef> columns = new ArrayList<>();

        for (String line : lines) {
            String trimmed = line.trim();

            // 解析表名
            if (tableName == null) {
                Matcher tm = TABLE_PATTERN.matcher(trimmed);
                if (tm.find()) {
                    tableName = tm.group(1);
                }
            }

            // 解析列定义
            Matcher cm = COL_PATTERN.matcher(line);
            if (cm.find()) {
                String colName = cm.group(1);
                int startPos = Integer.parseInt(cm.group(2));
                int endPos = Integer.parseInt(cm.group(3));
                columns.add(new ColumnDef(colName, startPos, endPos));
            }
        }

        if (tableName == null || columns.isEmpty()) {
            return null;
        }

        return new CtlDefinition(tableName, columns);
    }

    /**
     * 构建INSERT语句的列头部分：INSERT INTO `tableName` (col1, col2, ...) VALUES
     */
    private static String buildColumnHeader(CtlDefinition ctlDef) {
        StringBuilder sb = new StringBuilder();
        sb.append("INSERT INTO `").append(ctlDef.tableName).append("` (");
        StringJoiner colJoiner = new StringJoiner(", ");
        for (ColumnDef col : ctlDef.columns) {
            colJoiner.add(col.name);
        }
        sb.append(colJoiner).append(") VALUES ");
        return sb.toString();
    }

    /**
     * 根据CTL定义和数据行字节数组，构建单行值元组：(val1, val2, ...)
     */
    private static String buildValueTuple(CtlDefinition ctlDef, byte[] lineBytes, Charset datCharset) {
        StringJoiner valJoiner = new StringJoiner(", ");
        for (ColumnDef col : ctlDef.columns) {
            String value = extractFieldByBytes(lineBytes, col.startPos, col.endPos, datCharset);
            // 对值中的单引号进行转义
            value = value.replace("'", "''");
            if (value.isEmpty()) {
                valJoiner.add("NULL");
            } else {
                valJoiner.add("'" + value + "'");
            }
        }
        return "(" + valJoiner + ")";
    }

    /**
     * 将积累的值元组批量写入文件
     */
    private static void flushBatch(BufferedWriter writer, String header, List<String> valueTuples) throws IOException {
        writer.write(header);
        for (int i = 0; i < valueTuples.size(); i++) {
            if (i > 0) {
                writer.write(',');
            }
            writer.write(valueTuples.get(i));
        }
        writer.write(";");
        writer.newLine();
    }

    /**
     * 从定宽数据行的字节数组中，按字节位置提取字段值（POSITION从1开始）
     */
    private static String extractFieldByBytes(byte[] lineBytes, int startPos, int endPos, Charset datCharset) {
        // POSITION是1-based，转为0-based索引
        int start = startPos - 1;
        int end = endPos;

        if (start >= lineBytes.length) {
            return "";
        }
        if (end > lineBytes.length) {
            end = lineBytes.length;
        }

        int len = end - start;
        byte[] fieldBytes = new byte[len];
        System.arraycopy(lineBytes, start, fieldBytes, 0, len);

        return new String(fieldBytes, datCharset).trim();
    }

    /**
     * CTL定义：表名 + 列定义列表
     */
    private static class CtlDefinition {
        final String tableName;
        final List<ColumnDef> columns;

        CtlDefinition(String tableName, List<ColumnDef> columns) {
            this.tableName = tableName;
            this.columns = columns;
        }
    }

    /**
     * 列定义：列名 + 起始位置 + 结束位置
     */
    private static class ColumnDef {
        final String name;
        final int startPos;
        final int endPos;

        ColumnDef(String name, int startPos, int endPos) {
            this.name = name;
            this.startPos = startPos;
            this.endPos = endPos;
        }
    }
}
