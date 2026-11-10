//package org.example;
//
//import org.apache.ibatis.io.Resources;
//import org.apache.ibatis.jdbc.ScriptRunner;
//import org.apache.ibatis.session.SqlSession;
//import org.apache.ibatis.session.SqlSessionFactory;
//import org.apache.ibatis.session.SqlSessionFactoryBuilder;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//
//import java.io.*;
//import java.nio.charset.StandardCharsets;
//import java.sql.Connection;
//import java.util.*;
//import java.util.concurrent.*;
//import java.util.concurrent.atomic.AtomicInteger;
//
///**
// * 使用MyBatis ScriptRunner批量执行SQL文件。
// * - SQL文件路径从配置文件读取
// * - 每个文件一个线程处理，线程池大小为CPU核心数
// * - 每达到batch.commit.size条时提交一次，避免一次性提交超大数据量
// * - 执行失败回滚当前批次并记录日志
// */
//public class BatchSqlExecutor {
//
//    private static final Logger logger = LoggerFactory.getLogger(BatchSqlExecutor.class);
//
//    private final SqlSessionFactory sqlSessionFactory;
//    private final String sqlInputDir;
//    private final int batchCommitSize;
//
//    public BatchSqlExecutor(SqlSessionFactory sqlSessionFactory, String sqlInputDir, int batchCommitSize) {
//        this.sqlSessionFactory = sqlSessionFactory;
//        this.sqlInputDir = sqlInputDir;
//        this.batchCommitSize = batchCommitSize;
//    }
//
//    public static void main(String[] args) {
//        try {
//            // 1. 加载业务配置
//            Properties config = loadProperties("application.properties");
//            String sqlInputDir = config.getProperty("batch.sql.input.dir");
//            int batchCommitSize = Integer.parseInt(config.getProperty("batch.commit.size", "1000"));
//
//            // 2. 构建MyBatis SqlSessionFactory
//            SqlSessionFactory sqlSessionFactory = buildSqlSessionFactory(config);
//
//            // 3. 执行
//            BatchSqlExecutor executor = new BatchSqlExecutor(sqlSessionFactory, sqlInputDir, batchCommitSize);
//            executor.execute();
//
//        } catch (Exception e) {
//            logger.error("程序启动失败", e);
//        }
//    }
//
//    /**
//     * 主执行方法：扫描SQL文件并用线程池并发执行
//     */
//    public void execute() {
//        File inputFolder = new File(sqlInputDir);
//        File[] sqlFiles = inputFolder.listFiles((dir, name) -> name.toLowerCase().endsWith(".sql"));
//
//        if (sqlFiles == null || sqlFiles.length == 0) {
//            logger.warn("SQL文件目录下未找到任何.sql文件: {}", sqlInputDir);
//            return;
//        }
//
//        // 线程池大小为CPU核心数
//        int cpuCores = Runtime.getRuntime().availableProcessors();
//        logger.info("CPU核心数: {}, SQL文件数: {}, 批量提交数: {}", cpuCores, sqlFiles.length, batchCommitSize);
//
//        ExecutorService threadPool = Executors.newFixedThreadPool(cpuCores, new ThreadFactory() {
//            private final AtomicInteger counter = new AtomicInteger(1);
//            @Override
//            public Thread newThread(Runnable r) {
//                Thread t = new Thread(r, "sql-executor-" + counter.getAndIncrement());
//                t.setDaemon(false);
//                return t;
//            }
//        });
//
//        // 提交每个文件的任务
//        List<Future<FileExecutionResult>> futures = new ArrayList<>();
//        for (File sqlFile : sqlFiles) {
//            futures.add(threadPool.submit(() -> executeSqlFile(sqlFile)));
//        }
//
//        // 等待所有任务完成并汇总结果
//        int successCount = 0;
//        int failCount = 0;
//        for (Future<FileExecutionResult> future : futures) {
//            try {
//                FileExecutionResult result = future.get();
//                if (result.success) {
//                    successCount++;
//                    logger.info("文件执行成功: {}", result.fileName);
//                } else {
//                    failCount++;
//                    logger.error("文件执行失败: {} - {}", result.fileName, result.errorMessage);
//                }
//            } catch (Exception e) {
//                failCount++;
//                logger.error("获取任务结果异常", e);
//            }
//        }
//
//        threadPool.shutdown();
//        logger.info("===== 执行完毕 ===== 成功: {}个文件, 失败: {}个文件", successCount, failCount);
//    }
//
//    /**
//     * 执行单个SQL文件：使用ScriptRunner每达到batchCommitSize条提交一次，失败则回滚当前批次
//     */
//    private FileExecutionResult executeSqlFile(File sqlFile) {
//        String fileName = sqlFile.getName();
//        logger.info("[{}] 开始执行...", fileName);
//
//        int totalSql = 0;
//        int committedSql = 0;
//
//        try (SqlSession sqlSession = sqlSessionFactory.openSession(false)) {
//            Connection connection = sqlSession.getConnection();
//            connection.setAutoCommit(false);
//
//            try (BufferedReader reader = new BufferedReader(
//                    new InputStreamReader(new FileInputStream(sqlFile), StandardCharsets.UTF_8))) {
//
//                StringBuilder batchScript = new StringBuilder();
//                int batchCount = 0;
//                String line;
//
//                while ((line = reader.readLine()) != null) {
//                    String sql = line.trim();
//                    if (sql.isEmpty() || sql.startsWith("--")) {
//                        continue;
//                    }
//                    if (!sql.endsWith(";")) {
//                        sql = sql + ";";
//                    }
//
//                    batchScript.append(sql).append("\n");
//                    batchCount++;
//                    totalSql++;
//
//                    // 达到批次大小，用ScriptRunner执行并提交
//                    if (batchCount >= batchCommitSize) {
//                        runBatchAndCommit(connection, batchScript.toString(), fileName, committedSql, batchCount);
//                        committedSql += batchCount;
//                        batchScript.setLength(0);
//                        batchCount = 0;
//                    }
//                }
//
//                // 处理剩余的SQL
//                if (batchCount > 0) {
//                    runBatchAndCommit(connection, batchScript.toString(), fileName, committedSql, batchCount);
//                    committedSql += batchCount;
//                }
//            }
//
//            logger.info("[{}] 执行完成, 总SQL: {}, 已提交: {}", fileName, totalSql, committedSql);
//            return new FileExecutionResult(fileName, true, null);
//
//        } catch (Exception e) {
//            logger.error("[{}] 执行异常, 已提交: {}条. 错误: {}", fileName, committedSql, e.getMessage(), e);
//            return new FileExecutionResult(fileName, false, e.getMessage());
//        }
//    }
//
//    /**
//     * 使用ScriptRunner执行一批SQL并提交，失败则回滚本批次
//     */
//    private void runBatchAndCommit(Connection connection, String script,
//                                   String fileName, int alreadyCommitted, int batchCount) {
//        try {
//            ScriptRunner scriptRunner = new ScriptRunner(connection);
//            scriptRunner.setAutoCommit(false);
//            scriptRunner.setStopOnError(true);
//            scriptRunner.setSendFullScript(false);
//            scriptRunner.setLogWriter(null);
//            scriptRunner.setErrorLogWriter(null);
//
//            scriptRunner.runScript(new StringReader(script));
//            connection.commit();
//
//            logger.debug("[{}] 批次提交成功, 本批: {}条, 累计: {}条",
//                    fileName, batchCount, alreadyCommitted + batchCount);
//
//        } catch (Exception e) {
//            logger.error("[{}] 批次执行失败(第{}~{}条), 执行回滚. 错误: {}",
//                    fileName, alreadyCommitted + 1, alreadyCommitted + batchCount, e.getMessage());
//            try {
//                connection.rollback();
//                logger.info("[{}] 回滚成功", fileName);
//            } catch (Exception rollbackEx) {
//                logger.error("[{}] 回滚失败!", fileName, rollbackEx);
//            }
//            throw new RuntimeException("批次执行失败，文件: " + fileName + ", 错误: " + e.getMessage(), e);
//        }
//    }
//
//    /**
//     * 构建MyBatis SqlSessionFactory，用配置文件中的数据库参数替换mybatis-config.xml中的占位符
//     */
//    private static SqlSessionFactory buildSqlSessionFactory(Properties config) throws Exception {
//        Properties mybatisProps = new Properties();
//        mybatisProps.setProperty("db.driver", config.getProperty("db.driver"));
//        mybatisProps.setProperty("db.url", config.getProperty("db.url"));
//        mybatisProps.setProperty("db.username", config.getProperty("db.username"));
//        mybatisProps.setProperty("db.password", config.getProperty("db.password"));
//
//        try (InputStream is = Resources.getResourceAsStream("mybatis-config.xml")) {
//            return new SqlSessionFactoryBuilder().build(is, mybatisProps);
//        }
//    }
//
//    /**
//     * 加载classpath下的properties配置文件
//     */
//    private static Properties loadProperties(String fileName) throws Exception {
//        Properties props = new Properties();
//        try (InputStream is = BatchSqlExecutor.class.getClassLoader().getResourceAsStream(fileName)) {
//            if (is == null) {
//                throw new FileNotFoundException("找不到配置文件: " + fileName);
//            }
//            props.load(new InputStreamReader(is, StandardCharsets.UTF_8));
//        }
//        return props;
//    }
//
//    /**
//     * 文件执行结果
//     */
//    private static class FileExecutionResult {
//        final String fileName;
//        final boolean success;
//        final String errorMessage;
//
//        FileExecutionResult(String fileName, boolean success, String errorMessage) {
//            this.fileName = fileName;
//            this.success = success;
//            this.errorMessage = errorMessage;
//        }
//    }
//}
