package org.example;

import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class JdbcUtil {

    private static final String URL;
    private static final String USERNAME;
    private static final String PASSWORD;
    private static final String DRIVER_CLASS_NAME;

    static {
        try {
            Properties properties = loadYamlProperties();
            URL = getRequiredProperty(properties, "spring.datasource.url");
            USERNAME = getRequiredProperty(properties, "spring.datasource.username");
            PASSWORD = getRequiredProperty(properties, "spring.datasource.password");
            DRIVER_CLASS_NAME = getRequiredProperty(properties, "spring.datasource.driver-class-name");
            Class.forName(DRIVER_CLASS_NAME);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(
                    new IllegalStateException("数据库配置或驱动加载失败", e)
            );
        }
    }

    private JdbcUtil() {
    }

    private static Properties loadYamlProperties() {
        ClassPathResource resource = new ClassPathResource("application.yml");
        if (!resource.exists()) {
            throw new IllegalStateException("类路径下未找到 application.yml");
        }

        YamlPropertiesFactoryBean yamlFactory = new YamlPropertiesFactoryBean();
        yamlFactory.setResources(resource);
        Properties properties = yamlFactory.getObject();
        if (properties == null) {
            throw new IllegalStateException("application.yml 读取失败");
        }
        return properties;
    }

    private static String getRequiredProperty(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("application.yml 缺少配置项: " + key);
        }
        return value;
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
                URL,
                USERNAME,
                PASSWORD
        );
    }

    public static void main(String[] args) {
        String sql = "select * from test1 ";
        List<Map<String, Object>> result = querySqlToMap(sql);
        System.out.println(result);
    }
}
