package org.example;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 将 FLG 定宽字段定义批量转换为 Oracle SQL*Loader CTL、建表和回退脚本。
 */
public final class FlgToCtlConverter {

    private static final Path DEFAULT_FLG_DIRECTORY =
            Path.of("src", "main", "resources", "file", "flg");
    private static final Path DEFAULT_CTL_DIRECTORY =
            Path.of("src", "main", "resources", "file", "ctl");
    private static final Path DEFAULT_TBL_DIRECTORY =
            Path.of("src", "main", "resources", "file", "tbl");
    private static final Path DEFAULT_DROP_DIRECTORY =
            Path.of("src", "main", "resources", "file", "drop");

    private static final Pattern COLUMN_PATTERN = Pattern.compile(
            "^\\s*(\\d+)\\$\\$([^$]+)\\$\\$([^$]+)\\$\\$"
                    + "\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)\\s*$");

    private static final String ORACLE_IDENTIFIER =
            "(?:\\\"[^\\\"]+\\\"|[A-Za-z][A-Za-z0-9_$#]*)";
    private static final Pattern TABLE_PATTERN = Pattern.compile(
            "\\bFROM\\s+(" + ORACLE_IDENTIFIER
                    + "(?:\\s*\\.\\s*" + ORACLE_IDENTIFIER + ")?)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COLUMN_NAME_PATTERN = Pattern.compile(ORACLE_IDENTIFIER);
    private static final Pattern SOURCE_TYPE_PATTERN = Pattern.compile(
            "^([A-Za-z]+(?:\\s+[A-Za-z]+)?)"
                    + "(?:\\s*\\(\\s*([0-9]+(?:\\s*,\\s*[0-9]+)?"
                    + "(?:\\s+(?:BYTE|CHAR))?)\\s*\\))?$",
            Pattern.CASE_INSENSITIVE);

    private FlgToCtlConverter() {
    }

    /**
     * 使用默认路径批量转换：
     * src/main/resources/file/flg -> file/ctl、file/tbl、file/drop。
     *
     * @return 生成的 CTL 文件路径
     */
    public static List<Path> convertAll() throws IOException {
        return convertAll(
                DEFAULT_FLG_DIRECTORY,
                DEFAULT_CTL_DIRECTORY,
                DEFAULT_TBL_DIRECTORY,
                DEFAULT_DROP_DIRECTORY,
                StandardCharsets.UTF_8);
    }

    /**
     * 批量转换指定目录下的所有 .flg 文件。
     */
    public static List<Path> convertAll(Path flgDirectory,
                                        Path ctlDirectory,
                                        Charset charset) throws IOException {
        return convertAll(
                flgDirectory,
                ctlDirectory,
                ctlDirectory.resolveSibling("tbl"),
                ctlDirectory.resolveSibling("drop"),
                charset);
    }

    /**
     * 批量生成 CTL、建表和回退脚本。
     *
     * @return 生成的 CTL 文件路径
     */
    public static List<Path> convertAll(Path flgDirectory,
                                        Path ctlDirectory,
                                        Path tblDirectory,
                                        Path dropDirectory,
                                        Charset charset) throws IOException {
        if (flgDirectory == null || ctlDirectory == null
                || tblDirectory == null || dropDirectory == null || charset == null) {
            throw new IllegalArgumentException("目录和字符集不能为空");
        }
        if (!Files.isDirectory(flgDirectory)) {
            throw new IOException("FLG 目录不存在: " + flgDirectory.toAbsolutePath());
        }

        createOutputDirectories(ctlDirectory, tblDirectory, dropDirectory);

        List<Path> flgFiles;
        try (Stream<Path> files = Files.list(flgDirectory)) {
            flgFiles = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString()
                            .toLowerCase(Locale.ROOT).endsWith(".flg"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }

        List<Path> generatedFiles = new ArrayList<>();
        for (Path flgFile : flgFiles) {
            generatedFiles.add(convert(
                    flgFile,
                    ctlDirectory,
                    tblDirectory,
                    dropDirectory,
                    charset));
        }
        return generatedFiles;
    }

    /**
     * 转换单个 FLG 文件。
     */
    public static Path convert(Path flgFile,
                               Path ctlDirectory,
                               Charset charset) throws IOException {
        return convert(
                flgFile,
                ctlDirectory,
                ctlDirectory.resolveSibling("tbl"),
                ctlDirectory.resolveSibling("drop"),
                charset);
    }

    /**
     * 转换单个 FLG 文件，同时生成 CTL、建表和回退脚本。
     *
     * @return 生成的 CTL 文件路径
     */
    public static Path convert(Path flgFile,
                               Path ctlDirectory,
                               Path tblDirectory,
                               Path dropDirectory,
                               Charset charset) throws IOException {
        if (flgFile == null || ctlDirectory == null
                || tblDirectory == null || dropDirectory == null || charset == null) {
            throw new IllegalArgumentException("文件、目录和字符集不能为空");
        }
        if (!Files.isRegularFile(flgFile)) {
            throw new IOException("FLG 文件不存在: " + flgFile.toAbsolutePath());
        }

        FlgDefinition definition = parse(flgFile, charset);
        String ctl = buildCtl(definition);
        String createTableSql = buildCreateTableSql(definition);
        String dropTableSql = buildDropTableSql(definition);

        createOutputDirectories(ctlDirectory, tblDirectory, dropDirectory);
        String baseName = toOutputFileBaseName(definition.tableName());
        Path ctlFile = ctlDirectory.resolve(baseName + ".ctl");
        Path tblFile = tblDirectory.resolve(baseName + ".sql");
        Path dropFile = dropDirectory.resolve(baseName + ".sql");

        writeFile(ctlFile, ctl, charset);
        writeFile(tblFile, createTableSql, charset);
        writeFile(dropFile, dropTableSql, charset);
        return ctlFile;
    }

    private static FlgDefinition parse(Path flgFile, Charset charset) throws IOException {
        List<String> lines = Files.readAllLines(flgFile, charset);
        Map<String, String> properties = new HashMap<>();
        List<ColumnDefinition> columns = new ArrayList<>();
        boolean readingColumns = false;

        for (int i = 0; i < lines.size(); i++) {
            String line = removeBom(lines.get(i));
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            if (readingColumns) {
                columns.add(parseColumn(trimmed, flgFile, i + 1));
                continue;
            }

            int equalsIndex = trimmed.indexOf('=');
            if (equalsIndex < 0) {
                // 第一行通常是数据文件摘要，不影响 CTL 生成。
                continue;
            }

            String key = trimmed.substring(0, equalsIndex).trim().toUpperCase(Locale.ROOT);
            String value = trimmed.substring(equalsIndex + 1).trim();
            if ("COLUMNDESCRIPTION".equals(key)) {
                readingColumns = true;
                if (!value.isEmpty()) {
                    columns.add(parseColumn(value, flgFile, i + 1));
                }
            } else {
                properties.put(key, value);
            }
        }

        validateDefinition(flgFile, properties, columns);
        columns.sort(Comparator.comparingInt(ColumnDefinition::index));

        String tableName = parseTableName(properties.get("SQL"), flgFile);
        return new FlgDefinition(tableName, columns);
    }

    private static ColumnDefinition parseColumn(String line,
                                                Path flgFile,
                                                int lineNumber) throws IOException {
        Matcher matcher = COLUMN_PATTERN.matcher(line);
        if (!matcher.matches()) {
            throw formatError(flgFile, lineNumber,
                    "列定义格式错误，应为 序号$$列名$$类型$$(起始位,结束位): " + line);
        }

        int index = parsePositiveInt(matcher.group(1), flgFile, lineNumber, "列序号");
        String name = matcher.group(2).trim();
        String sourceType = matcher.group(3).trim();
        int start = parsePositiveInt(matcher.group(4), flgFile, lineNumber, "起始位置");
        int end = parsePositiveInt(matcher.group(5), flgFile, lineNumber, "结束位置");

        if (!COLUMN_NAME_PATTERN.matcher(name).matches()) {
            throw formatError(flgFile, lineNumber, "非法列名: " + name);
        }
        if (sourceType.isEmpty()) {
            throw formatError(flgFile, lineNumber, "字段类型不能为空");
        }
        if (end < start) {
            throw formatError(flgFile, lineNumber,
                    "结束位置不能小于起始位置: " + start + "," + end);
        }

        return new ColumnDefinition(index, name, sourceType, start, end);
    }

    private static void validateDefinition(Path flgFile,
                                           Map<String, String> properties,
                                           List<ColumnDefinition> columns) throws IOException {
        requireProperty(flgFile, properties, "SQL");
        if (columns.isEmpty()) {
            throw new IOException("FLG 文件没有 COLUMNDESCRIPTION: " + flgFile.toAbsolutePath());
        }

        String declaredColumnCount = properties.get("COLUMNCOUNT");
        if (declaredColumnCount != null && !declaredColumnCount.isBlank()) {
            int count = parsePositiveInt(declaredColumnCount, flgFile, 0, "COLUMNCOUNT");
            if (count != columns.size()) {
                throw new IOException("COLUMNCOUNT=" + count + "，实际列定义=" + columns.size()
                        + "，文件: " + flgFile.toAbsolutePath());
            }
        }

        Set<Integer> indexes = new HashSet<>();
        for (ColumnDefinition column : columns) {
            if (!indexes.add(column.index())) {
                throw new IOException("存在重复列序号 " + column.index()
                        + "，文件: " + flgFile.toAbsolutePath());
            }
        }

        String declaredRowLength = properties.get("ROWLENGTH");
        if (declaredRowLength != null && !declaredRowLength.isBlank()) {
            int rowLength = parsePositiveInt(declaredRowLength, flgFile, 0, "ROWLENGTH");
            int lastPosition = columns.stream()
                    .mapToInt(ColumnDefinition::end)
                    .max()
                    .orElse(0);
            if (rowLength != lastPosition) {
                throw new IOException("ROWLENGTH=" + rowLength
                        + "，最后一个字段结束位置=" + lastPosition
                        + "，文件: " + flgFile.toAbsolutePath());
            }
        }
    }

    private static String parseTableName(String sql, Path flgFile) throws IOException {
        Matcher matcher = TABLE_PATTERN.matcher(sql);
        if (!matcher.find()) {
            throw new IOException("无法从 SQL 中解析 FROM 表名: " + sql
                    + "，文件: " + flgFile.toAbsolutePath());
        }
        return matcher.group(1).replaceAll("\\s*\\.\\s*", ".");
    }

    private static String buildCtl(FlgDefinition definition) {
        String newLine = System.lineSeparator();
        StringBuilder ctl = new StringBuilder();
        ctl.append("LOAD DATA").append(newLine);
        ctl.append("TRUNCATE INTO TABLE ")
                .append(toCtlTableName(definition.tableName()))
                .append(newLine);
        ctl.append("TRAILING NULLCOLS (").append(newLine);

        for (int i = 0; i < definition.columns().size(); i++) {
            ColumnDefinition column = definition.columns().get(i);
            String columnName = toCtlIdentifier(column.name());
            String position = "POSITION(" + column.start() + ":" + column.end() + ")";
            ctl.append(String.format(
                    Locale.ROOT,
                    "    %-26s %-30s \"TRIM(:%s)\"",
                    columnName,
                    position,
                    columnName));
            if (i < definition.columns().size() - 1) {
                ctl.append(',');
            }
            ctl.append(newLine);
        }
        ctl.append(')').append(newLine);
        return ctl.toString();
    }

    private static String buildCreateTableSql(FlgDefinition definition) throws IOException {
        String newLine = System.lineSeparator();
        StringBuilder sql = new StringBuilder();
        sql.append("CREATE TABLE IF NOT EXISTS ")
                .append(toCtlTableName(definition.tableName()))
                .append(" (")
                .append(newLine);

        for (int i = 0; i < definition.columns().size(); i++) {
            ColumnDefinition column = definition.columns().get(i);
            sql.append(String.format(
                    Locale.ROOT,
                    "    %-30s %s",
                    toCtlIdentifier(column.name()),
                    toOracleDataType(column)));
            if (i == 0) {
                sql.append(" PRIMARY KEY");
            }
            if (i < definition.columns().size() - 1) {
                sql.append(',');
            }
            sql.append(newLine);
        }

        sql.append(");").append(newLine);
        return sql.toString();
    }

    private static String buildDropTableSql(FlgDefinition definition) {
        return "DROP TABLE IF EXISTS "
                + toCtlTableName(definition.tableName())
                + ";"
                + System.lineSeparator();
    }

    private static String toOracleDataType(ColumnDefinition column) throws IOException {
        Matcher matcher = SOURCE_TYPE_PATTERN.matcher(column.sourceType().trim());
        if (!matcher.matches()) {
            throw new IOException("不支持的字段类型 " + column.sourceType()
                    + "，字段: " + column.name());
        }

        String sourceType = matcher.group(1)
                .trim()
                .replaceAll("\\s+", " ")
                .toUpperCase(Locale.ROOT);
        String parameters = normalizeTypeParameters(matcher.group(2));
        int fieldLength = column.end() - column.start() + 1;

        return switch (sourceType) {
            case "CHAR", "VARCHAR", "VARCHAR2" ->
                    "VARCHAR(" + expandVarcharLength(parameters, fieldLength, column) + ")";
            case "NCHAR" -> withParameters("NCHAR", parameters, String.valueOf(fieldLength));
            case "NVARCHAR", "NVARCHAR2" ->
                    withParameters("NVARCHAR2", parameters, String.valueOf(fieldLength));
            case "NUMBER", "NUMERIC", "DECIMAL", "DEC" ->
                    withOptionalParameters("NUMBER", parameters);
            case "INT", "INTEGER", "SMALLINT", "BIGINT" -> "NUMBER";
            case "FLOAT" -> withOptionalParameters("FLOAT", parameters);
            case "REAL" -> "BINARY_FLOAT";
            case "DOUBLE", "DOUBLE PRECISION" -> "BINARY_DOUBLE";
            case "DATE" -> "DATE";
            case "DATETIME" -> "TIMESTAMP";
            case "TIMESTAMP" -> withOptionalParameters("TIMESTAMP", parameters);
            case "CLOB", "NCLOB", "BLOB" -> sourceType;
            case "RAW" -> withParameters("RAW", parameters, String.valueOf(fieldLength));
            default -> throw new IOException("不支持的字段类型 " + column.sourceType()
                    + "，字段: " + column.name());
        };
    }

    private static String expandVarcharLength(String parameters,
                                              int fieldLength,
                                              ColumnDefinition column) throws IOException {
        String lengthText = parameters == null ? String.valueOf(fieldLength) : parameters;
        Matcher matcher = Pattern.compile("^(\\d+)(?:\\s+(BYTE|CHAR))?$",
                Pattern.CASE_INSENSITIVE).matcher(lengthText);
        if (!matcher.matches()) {
            throw new IOException("VARCHAR 长度格式错误 " + column.sourceType()
                    + "，字段: " + column.name());
        }

        try {
            int expandedLength = Math.multiplyExact(Integer.parseInt(matcher.group(1)), 3);
            String lengthSemantics = matcher.group(2);
            return lengthSemantics == null
                    ? String.valueOf(expandedLength)
                    : expandedLength + " " + lengthSemantics.toUpperCase(Locale.ROOT);
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IOException("VARCHAR 长度扩容后超出范围，字段: " + column.name(), e);
        }
    }

    private static String normalizeTypeParameters(String parameters) {
        if (parameters == null || parameters.isBlank()) {
            return null;
        }
        return parameters.trim()
                .replaceAll("\\s*,\\s*", ",")
                .replaceAll("\\s+", " ")
                .toUpperCase(Locale.ROOT);
    }

    private static String withParameters(String type,
                                         String parameters,
                                         String defaultParameters) {
        return type + "(" + (parameters == null ? defaultParameters : parameters) + ")";
    }

    private static String withOptionalParameters(String type, String parameters) {
        return parameters == null ? type : type + "(" + parameters + ")";
    }

    private static void createOutputDirectories(Path ctlDirectory,
                                                Path tblDirectory,
                                                Path dropDirectory) throws IOException {
        Files.createDirectories(ctlDirectory);
        Files.createDirectories(tblDirectory);
        Files.createDirectories(dropDirectory);
    }

    private static void writeFile(Path file,
                                  String content,
                                  Charset charset) throws IOException {
        Files.writeString(
                file,
                content,
                charset,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
    }

    private static String toCtlTableName(String qualifiedTableName) {
        int dotIndex = qualifiedTableName.lastIndexOf('.');
        String tableName = dotIndex >= 0
                ? qualifiedTableName.substring(dotIndex + 1)
                : qualifiedTableName;
        return toCtlIdentifier(tableName);
    }

    private static String toOutputFileBaseName(String qualifiedTableName) {
        String tableName = toCtlTableName(qualifiedTableName);
        if (tableName.startsWith("\"") && tableName.endsWith("\"")) {
            tableName = tableName.substring(1, tableName.length() - 1)
                    .replace("\"\"", "\"");
        }
        return tableName.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static String toCtlIdentifier(String identifier) {
        String trimmed = identifier.trim();
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed;
        }
        return trimmed.toUpperCase(Locale.ROOT);
    }

    private static void requireProperty(Path flgFile,
                                        Map<String, String> properties,
                                        String key) throws IOException {
        String value = properties.get(key);
        if (value == null || value.isBlank()) {
            throw new IOException("FLG 文件缺少 " + key + ": " + flgFile.toAbsolutePath());
        }
    }

    private static int parsePositiveInt(String value,
                                        Path flgFile,
                                        int lineNumber,
                                        String fieldName) throws IOException {
        try {
            int number = Integer.parseInt(value.trim());
            if (number <= 0) {
                throw new NumberFormatException("必须大于 0");
            }
            return number;
        } catch (NumberFormatException e) {
            throw formatError(flgFile, lineNumber,
                    fieldName + "不是有效正整数: " + value);
        }
    }

    private static IOException formatError(Path file, int lineNumber, String message) {
        String location = lineNumber > 0
                ? file.toAbsolutePath() + ":" + lineNumber
                : file.toAbsolutePath().toString();
        return new IOException(location + " - " + message);
    }

    private static String removeBom(String value) {
        return value.startsWith("\uFEFF") ? value.substring(1) : value;
    }

    private record FlgDefinition(String tableName,
                                 List<ColumnDefinition> columns) {
    }

    private record ColumnDefinition(int index,
                                    String name,
                                    String sourceType,
                                    int start,
                                    int end) {
    }

    public static void main(String[] args) {
        try {
            Path flgDirectory = args.length >= 1 ? Path.of(args[0]) : DEFAULT_FLG_DIRECTORY;
            Path ctlDirectory = args.length >= 2 ? Path.of(args[1]) : DEFAULT_CTL_DIRECTORY;
            Path tblDirectory = ctlDirectory.resolveSibling("tbl");
            Path dropDirectory = ctlDirectory.resolveSibling("drop");
            Charset charset = args.length >= 3
                    ? Charset.forName(args[2])
                    : StandardCharsets.UTF_8;

            List<Path> generatedFiles = convertAll(
                    flgDirectory,
                    ctlDirectory,
                    tblDirectory,
                    dropDirectory,
                    charset);
            if (generatedFiles.isEmpty()) {
                System.out.println("未找到 FLG 文件: " + flgDirectory.toAbsolutePath());
                return;
            }

            for (Path generatedFile : generatedFiles) {
                System.out.println("已生成 CTL: " + generatedFile.toAbsolutePath());
                String ctlFileName = generatedFile.getFileName().toString();
                String sqlFileName = ctlFileName.substring(0, ctlFileName.length() - 4) + ".sql";
                System.out.println("已生成建表脚本: "
                        + tblDirectory.resolve(sqlFileName).toAbsolutePath());
                System.out.println("已生成回退脚本: "
                        + dropDirectory.resolve(sqlFileName).toAbsolutePath());
            }
            System.out.println("转换完成，共处理 " + generatedFiles.size() + " 个 FLG 文件");
        } catch (Exception e) {
            System.err.println("FLG 转 CTL 失败: " + e.getMessage());
            System.exit(1);
        }
    }
}
