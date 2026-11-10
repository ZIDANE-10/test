package org.example;

import java.io.*;
import java.nio.charset.Charset;
import java.util.*;

/**
 * 文件编码转换工具类。
 * 提供静态方法，将指定文件从源编码转换为目标编码，
 * 原文件改名为.bak备份，转换后直接替换源文件。
 */
public class FileEncodingConverter {

    private FileEncodingConverter() {
    }

    /**
     * 执行编码转换（原文件改名为.bak，转换后直接替换源文件）
     *
     * @param filePaths     文件绝对路径列表
     * @param sourceCharset 源编码（如 "GBK"）
     * @param targetCharset 目标编码（如 "UTF-8"）
     */
    public static void convert(List<String> filePaths, String sourceCharset, String targetCharset) {
        if (filePaths == null || filePaths.isEmpty()) {
            System.out.println("未指定需要转换的文件");
            return;
        }

        Charset srcCharset = Charset.forName(sourceCharset);
        Charset tgtCharset = Charset.forName(targetCharset);

        System.out.println("编码转换: " + srcCharset.name() + " -> " + tgtCharset.name());
        System.out.println("待转换文件数: " + filePaths.size());
        System.out.println("----------------------------------------");

        int successCount = 0;
        int failCount = 0;

        for (String filePath : filePaths) {
            File file = new File(filePath);
            if (!file.exists() || !file.isFile()) {
                System.err.println("[跳过] 文件不存在: " + filePath);
                failCount++;
                continue;
            }
            try {
                convertFile(file, srcCharset, tgtCharset);
                System.out.println("[成功] " + file.getName());
                successCount++;
            } catch (Exception e) {
                System.err.println("[失败] " + file.getName() + " - " + e.getMessage());
                failCount++;
            }
        }

        System.out.println("----------------------------------------");
        System.out.println("转换完成: 成功 " + successCount + " 个, 失败 " + failCount + " 个");
    }

    /**
     * 转换单个文件的编码：源文件改名为.bak，转换结果写入原文件路径
     */
    private static void convertFile(File sourceFile, Charset srcCharset, Charset tgtCharset) throws IOException {
        // 源文件改名为 .bak
        File bakFile = new File(sourceFile.getAbsolutePath() + ".bak");
        if (bakFile.exists()) {
            bakFile.delete();
        }
        if (!sourceFile.renameTo(bakFile)) {
            throw new IOException("无法将源文件改名为.bak: " + sourceFile.getName());
        }

        // 从.bak文件读取，以目标编码写入原文件路径
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(bakFile), srcCharset));
             BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(sourceFile), tgtCharset))) {

            char[] buffer = new char[8192];
            int charsRead;
            while ((charsRead = reader.read(buffer)) != -1) {
                writer.write(buffer, 0, charsRead);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        // 加载配置文件
        Properties props = new Properties();
        try (InputStream is = FileEncodingConverter.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (is == null) {
                throw new FileNotFoundException("找不到配置文件 application.properties");
            }
            props.load(new InputStreamReader(is, "UTF-8"));
        }

        String inputDir = props.getProperty("encoding.convert.input.dir");
        String sourceCharset = props.getProperty("encoding.convert.source.charset", "GBK");
        String targetCharset = props.getProperty("encoding.convert.target.charset", "UTF-8");
        String fileNamesStr = props.getProperty("encoding.convert.file.names", "").trim();

        // 解析文件名列表，拼接为完整路径
        List<String> filePaths = new ArrayList<>();
        if (!fileNamesStr.isEmpty()) {
            for (String name : fileNamesStr.split(",")) {
                String trimmed = name.trim();
                if (!trimmed.isEmpty()) {
                    filePaths.add(inputDir + File.separator + trimmed);
                }
            }
        } else {
            // 未指定文件名，转换目录下所有文件
            File dir = new File(inputDir);
            File[] allFiles = dir.listFiles(File::isFile);
            if (allFiles != null) {
                for (File f : allFiles) {
                    filePaths.add(f.getAbsolutePath());
                }
            }
        }

        convert(filePaths, sourceCharset, targetCharset);
    }
}
