package org.example;

import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class JdbcUtil {

    private static String url="jdbc:postgresql://192.168.88.85:5432/awp?currentSchema=awp_dev";
    private static String username="iw";
    private static String password="iw123";
    private static String driverClassName = "org.postgresql.Driver";

    static  {
        try {
            Class.forName(driverClassName);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("数据库驱动加载失败", e);
        }
    }


    /**
     * 查询SQL返回List<Map>
     */
    public static List<Map<String, Object>> querySqlToMap(
            String sql,
            Object... params) {

        List<Map<String, Object>> result = new ArrayList<>();

        try (
                Connection conn = getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)
        ) {

            if (params != null) {
                for (int i = 0; i < params.length; i++) {
                    ps.setObject(i + 1, params[i]);
                }
            }


            try (ResultSet rs = ps.executeQuery()) {

                ResultSetMetaData meta =
                        rs.getMetaData();

                int columnCount =
                        meta.getColumnCount();


                while (rs.next()) {

                    Map<String, Object> map =
                            new LinkedHashMap<>();

                    for (int i = 1; i <= columnCount; i++) {

                        String column =
                                meta.getColumnLabel(i);

                        map.put(
                                column,
                                rs.getObject(i)
                        );
                    }

                    result.add(map);
                }
            }

        } catch (SQLException e) {
            throw new RuntimeException(
                    "SQL执行失败: " + sql,
                    e
            );
        }

        return result;
    }


    /**
     * 获取数据库连接，调用方负责关闭连接。
     */
    public static Connection getConnection()
            throws SQLException {

        return DriverManager.getConnection(
                url,
                username,
                password
        );
    }

    public static void main(String[] args) {
        String sql = "select * from test1 ";
        List<Map<String, Object>> result = querySqlToMap(sql);
        System.out.println(result);
    }
}
