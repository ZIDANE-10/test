package org.example;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 日期工具类
 */
public class DateUtil {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    private DateUtil() {
    }

    /**
     * 获取输入日期的前一天
     *
     * @param dateStr 日期字符串，格式 yyyyMMdd
     * @return 前一天的日期字符串，格式 yyyyMMdd
     */
    public static String minusOneDay(String dateStr) {
        LocalDate date = LocalDate.parse(dateStr, FORMATTER);
        return date.minusDays(1).format(FORMATTER);
    }
}
