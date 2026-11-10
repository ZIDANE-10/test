package org.example;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 生成 PostgreSQL VARCHAR 字段扩容 SQL。
 */
public final class VarcharExpandSqlGenerator {

    private static final int EXPAND_FACTOR = 3;

    private VarcharExpandSqlGenerator() {
    }

    /**
     * 为指定表中所有带长度限制的 VARCHAR 字段生成三倍扩容 SQL。
     * 表名支持 {@code table_name} 和 {@code schema_name.table_name} 两种格式。
     *
     * @param tableNames 要处理的表名
     * @return ALTER TABLE SQL 列表，顺序与入参表顺序及字段定义顺序一致
     */
    public static List<String> generateAlterTableSql(List<String> tableNames) {
        Objects.requireNonNull(tableNames, "tableNames 不能为空");
        if (tableNames.isEmpty()) {
            return List.of();
        }

        try (Connection connection = JdbcUtil.getConnection()) {
            return generateAlterTableSql(connection, tableNames);
        } catch (SQLException e) {
            throw new RuntimeException("生成 VARCHAR 扩容 SQL 失败", e);
        }
    }

    static List<String> generateAlterTableSql(Connection connection, List<String> tableNames)
            throws SQLException {
        Objects.requireNonNull(connection, "connection 不能为空");

        DatabaseMetaData metaData = connection.getMetaData();
        String quote = metaData.getIdentifierQuoteString();
        if (quote == null || quote.isBlank()) {
            quote = "\"";
        } else {
            quote = quote.trim();
        }

        List<String> sqlList = new ArrayList<>();
        for (String tableName : tableNames) {
            QualifiedTable table = parseTableName(tableName, connection, metaData, quote);
            appendTableSql(metaData, table, quote, sqlList);
        }
        return sqlList;
    }

    private static void appendTableSql(DatabaseMetaData metaData,
                                       QualifiedTable table,
                                       String quote,
                                       List<String> sqlList) throws SQLException {
        boolean tableFound = false;

        try (ResultSet columns = metaData.getColumns(
                table.catalog(), table.schema(), table.table(), null)) {
            while (columns.next()) {
                if (!same(columns.getString("TABLE_CAT"), table.catalog())
                        || !same(columns.getString("TABLE_SCHEM"), table.schema())
                        || !same(columns.getString("TABLE_NAME"), table.table())) {
                    continue;
                }

                tableFound = true;
                if (columns.getInt("DATA_TYPE") != Types.VARCHAR) {
                    continue;
                }

                int currentLength = columns.getInt("COLUMN_SIZE");
                // PostgreSQL 中无长度限制的 VARCHAR 无需扩容。
                if (currentLength <= 0 || currentLength == Integer.MAX_VALUE) {
                    continue;
                }

                long expandedLength = Math.multiplyExact((long) currentLength, EXPAND_FACTOR);
                String actualTable = columns.getString("TABLE_NAME");
                String columnName = columns.getString("COLUMN_NAME");

                sqlList.add("ALTER TABLE "
                        + quoteIdentifier(actualTable, quote)
                        + " ALTER COLUMN " + quoteIdentifier(columnName, quote)
                        + " TYPE VARCHAR(" + expandedLength + ");");
            }
        }

        if (!tableFound) {
            throw new IllegalArgumentException("未找到表: " + table.displayName());
        }
    }

    private static QualifiedTable parseTableName(String tableName,
                                                  Connection connection,
                                                  DatabaseMetaData metaData,
                                                  String quote) throws SQLException {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("表名不能为空");
        }

        List<Identifier> parts = splitQualifiedName(tableName.trim(), quote);
        String catalog = connection.getCatalog();
        String schema = connection.getSchema();
        String table;

        if (parts.size() == 1) {
            table = normalizeIdentifier(parts.get(0), metaData);
        } else if (parts.size() == 2) {
            schema = normalizeIdentifier(parts.get(0), metaData);
            table = normalizeIdentifier(parts.get(1), metaData);
        } else if (parts.size() == 3) {
            catalog = normalizeIdentifier(parts.get(0), metaData);
            schema = normalizeIdentifier(parts.get(1), metaData);
            table = normalizeIdentifier(parts.get(2), metaData);
        } else {
            throw new IllegalArgumentException("表名格式错误: " + tableName);
        }

        return new QualifiedTable(catalog, schema, table, tableName);
    }

    private static List<Identifier> splitQualifiedName(String name, String quote) {
        List<Identifier> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean currentWasQuoted = false;

        for (int i = 0; i < name.length(); i++) {
            if (!quote.isEmpty() && name.startsWith(quote, i)) {
                if (quoted && name.startsWith(quote, i + quote.length())) {
                    current.append(quote);
                    i += quote.length() * 2 - 1;
                    continue;
                }
                quoted = !quoted;
                currentWasQuoted = true;
                i += quote.length() - 1;
            } else if (name.charAt(i) == '.' && !quoted) {
                addIdentifier(result, current, currentWasQuoted, name);
                current = new StringBuilder();
                currentWasQuoted = false;
            } else {
                current.append(name.charAt(i));
            }
        }

        if (quoted) {
            throw new IllegalArgumentException("表名引号未闭合: " + name);
        }
        addIdentifier(result, current, currentWasQuoted, name);
        return result;
    }

    private static void addIdentifier(List<Identifier> result,
                                      StringBuilder value,
                                      boolean quoted,
                                      String originalName) {
        String identifier = quoted ? value.toString() : value.toString().trim();
        if (identifier.isEmpty()) {
            throw new IllegalArgumentException("表名格式错误: " + originalName);
        }
        result.add(new Identifier(identifier, quoted));
    }

    private static String normalizeIdentifier(Identifier identifier, DatabaseMetaData metaData)
            throws SQLException {
        if (identifier.quoted()) {
            return identifier.value();
        }
        if (metaData.storesUpperCaseIdentifiers()) {
            return identifier.value().toUpperCase();
        }
        if (metaData.storesLowerCaseIdentifiers()) {
            return identifier.value().toLowerCase();
        }
        return identifier.value();
    }

    private static String quoteIdentifier(String identifier, String quote) {
        return quote + identifier.replace(quote, quote + quote) + quote;
    }

    private static boolean same(String left, String right) {
        return Objects.equals(left, right);
    }

    private static final class Identifier {
        private final String value;
        private final boolean quoted;

        private Identifier(String value, boolean quoted) {
            this.value = value;
            this.quoted = quoted;
        }

        private String value() {
            return value;
        }

        private boolean quoted() {
            return quoted;
        }
    }

    private static final class QualifiedTable {
        private final String catalog;
        private final String schema;
        private final String table;
        private final String displayName;

        private QualifiedTable(String catalog, String schema, String table, String displayName) {
            this.catalog = catalog;
            this.schema = schema;
            this.table = table;
            this.displayName = displayName;
        }

        private String catalog() {
            return catalog;
        }

        private String schema() {
            return schema;
        }

        private String table() {
            return table;
        }

        private String displayName() {
            return displayName;
        }
    }

    public static void main(String[] args) {
        System.out.println(generateAlterTableSql(List.of("test1")));
    }
}
