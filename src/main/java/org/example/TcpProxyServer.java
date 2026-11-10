package org.example;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TCP 双向代理服务。
 * <p>
 * Agent 连接本地监听端口后，本服务会连接目标 TCP 服务，并在两端之间双向转发数据。
 * 每个数据包都会按十六进制打印；通过设置双向转发延迟，可以模拟请求或响应超时。
 */
public final class TcpProxyServer implements AutoCloseable {

    private static final int BUFFER_SIZE = 4096;
    private static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final Object LOG_LOCK = new Object();

    private final String targetHost;
    private final int targetPort;
    private final int agentPort;
    private final int connectTimeoutMillis;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong connectionSequence = new AtomicLong(0);
    private final Set<Socket> activeSockets = ConcurrentHashMap.newKeySet();
    private final ExecutorService workerPool = Executors.newCachedThreadPool(new ProxyThreadFactory());

    /** Agent -> 目标服务的转发延迟。 */
    private volatile long outboundDelayMillis;
    /** 目标服务 -> Agent 的转发延迟。 */
    private volatile long inboundDelayMillis;
    private volatile ServerSocket serverSocket;

    /**
     * 创建无模拟延迟的 TCP 代理。
     *
     * @param targetHost 被代理 TCP 服务的 IP 或域名
     * @param targetPort 被代理 TCP 服务的端口
     * @param agentPort  Agent 连接的本地监听端口
     */
    public TcpProxyServer(String targetHost, int targetPort, int agentPort) {
        this(targetHost, targetPort, agentPort, 0, 0);
    }

    /**
     * 创建 TCP 代理，并设置双向模拟延迟。
     *
     * @param targetHost          被代理 TCP 服务的 IP 或域名
     * @param targetPort          被代理 TCP 服务的端口
     * @param agentPort           Agent 连接的本地监听端口
     * @param outboundDelayMillis Agent -> 目标服务转发前延迟，0 表示不延迟
     * @param inboundDelayMillis  目标服务 -> Agent 转发前延迟，0 表示不延迟
     */
    public TcpProxyServer(String targetHost,
                          int targetPort,
                          int agentPort,
                          long outboundDelayMillis,
                          long inboundDelayMillis) {
        this(targetHost, targetPort, agentPort, outboundDelayMillis,
                inboundDelayMillis, DEFAULT_CONNECT_TIMEOUT_MILLIS);
    }

    public TcpProxyServer(String targetHost,
                          int targetPort,
                          int agentPort,
                          long outboundDelayMillis,
                          long inboundDelayMillis,
                          int connectTimeoutMillis) {
        if (targetHost == null || targetHost.isBlank()) {
            throw new IllegalArgumentException("目标服务 IP/域名不能为空");
        }
        validatePort(targetPort, "目标服务端口");
        validatePort(agentPort, "Agent 监听端口");
        validateDelay(outboundDelayMillis, "出访延迟");
        validateDelay(inboundDelayMillis, "入访延迟");
        if (connectTimeoutMillis <= 0) {
            throw new IllegalArgumentException("连接超时时间必须大于 0");
        }

        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.agentPort = agentPort;
        this.outboundDelayMillis = outboundDelayMillis;
        this.inboundDelayMillis = inboundDelayMillis;
        this.connectTimeoutMillis = connectTimeoutMillis;
    }

    /** 兼容原来的构造参数顺序。 */
    @Deprecated
    public TcpProxyServer(int agentPort, String targetHost, int targetPort) {
        this(targetHost, targetPort, agentPort);
    }

    /**
     * 启动代理。该方法会阻塞当前线程，直到调用 {@link #stop()}。
     */
    public void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("TCP 代理已经启动");
        }

        try {
            ServerSocket listener = new ServerSocket();
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(agentPort));
            serverSocket = listener;

            log("TCP 代理已启动：Agent 端口=%d，目标服务=%s:%d，出访延迟=%dms，入访延迟=%dms",
                    agentPort, targetHost, targetPort, outboundDelayMillis, inboundDelayMillis);

            while (running.get()) {
                try {
                    Socket agentSocket = listener.accept();
                    configureSocket(agentSocket);
                    activeSockets.add(agentSocket);
                    long connectionId = connectionSequence.incrementAndGet();
                    ConnectionContext context = new ConnectionContext(connectionId);
                    log("[连接-%d][TCP建立] Agent 已连接：remote=%s，local=%s",
                            connectionId,
                            agentSocket.getRemoteSocketAddress(),
                            agentSocket.getLocalSocketAddress());
                    workerPool.execute(() -> handleConnection(context, agentSocket));
                } catch (SocketException e) {
                    if (running.get()) {
                        throw e;
                    }
                } catch (RejectedExecutionException e) {
                    if (running.get()) {
                        throw new IOException("代理工作线程已关闭", e);
                    }
                }
            }
        } finally {
            stop();
        }
    }

    /**
     * 动态设置模拟延迟。设置成大于调用方超时阈值的值，即可模拟超时。
     */
    public void setSimulatedDelayMillis(long outboundDelayMillis, long inboundDelayMillis) {
        validateDelay(outboundDelayMillis, "出访延迟");
        validateDelay(inboundDelayMillis, "入访延迟");
        this.outboundDelayMillis = outboundDelayMillis;
        this.inboundDelayMillis = inboundDelayMillis;
        log("模拟延迟已更新：出访=%dms，入访=%dms", outboundDelayMillis, inboundDelayMillis);
    }

    private void handleConnection(ConnectionContext context, Socket agentSocket) {
        Socket targetSocket = new Socket();
        activeSockets.add(targetSocket);
        boolean targetConnected = false;

        try {
            targetSocket.connect(new InetSocketAddress(targetHost, targetPort), connectTimeoutMillis);
            configureSocket(targetSocket);
            targetConnected = true;
            log("[连接-%d][TCP建立] 目标服务已连接：local=%s，remote=%s",
                    context.connectionId,
                    targetSocket.getLocalSocketAddress(),
                    targetSocket.getRemoteSocketAddress());

            CountDownLatch relayFinished = new CountDownLatch(2);
            submitRelay(new RelayTask(
                    context,
                    "出访 Agent -> 目标服务",
                    agentSocket,
                    targetSocket,
                    Peer.AGENT,
                    Peer.TARGET,
                    true,
                    relayFinished));
            submitRelay(new RelayTask(
                    context,
                    "入访 目标服务 -> Agent",
                    targetSocket,
                    agentSocket,
                    Peer.TARGET,
                    Peer.AGENT,
                    false,
                    relayFinished));

            relayFinished.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RejectedExecutionException e) {
            if (targetConnected) {
                logError("[连接-%d] 连接处理失败：%s", context.connectionId, e.getMessage());
            } else {
                logError("[连接-%d][TCP建立失败] 无法连接目标服务 %s:%d：%s",
                        context.connectionId, targetHost, targetPort, e.getMessage());
            }
        } finally {
            closeSocket(agentSocket);
            closeSocket(targetSocket);
            activeSockets.remove(agentSocket);
            activeSockets.remove(targetSocket);
            log("[连接-%d][会话结束] 两端 Socket 已关闭", context.connectionId);
        }
    }

    private void submitRelay(RelayTask relayTask) {
        workerPool.execute(relayTask);
    }

    private final class RelayTask implements Runnable {
        private final ConnectionContext context;
        private final String direction;
        private final Socket sourceSocket;
        private final Socket destinationSocket;
        private final Peer sourcePeer;
        private final Peer destinationPeer;
        private final boolean outbound;
        private final CountDownLatch finished;

        private RelayTask(ConnectionContext context,
                          String direction,
                          Socket sourceSocket,
                          Socket destinationSocket,
                          Peer sourcePeer,
                          Peer destinationPeer,
                          boolean outbound,
                          CountDownLatch finished) {
            this.context = context;
            this.direction = direction;
            this.sourceSocket = sourceSocket;
            this.destinationSocket = destinationSocket;
            this.sourcePeer = sourcePeer;
            this.destinationPeer = destinationPeer;
            this.outbound = outbound;
            this.finished = finished;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[BUFFER_SIZE];

            try {
                InputStream input = sourceSocket.getInputStream();
                OutputStream output = destinationSocket.getOutputStream();

                while (true) {
                    int length;
                    try {
                        length = input.read(buffer);
                    } catch (IOException e) {
                        logAbnormalDisconnect(sourcePeer, sourceSocket, "读取失败", e);
                        throw e;
                    }

                    if (length == -1) {
                        context.logPeerDisconnected(
                                sourcePeer,
                                sourceSocket,
                                "收到 EOF，对方已关闭发送方向");
                        break;
                    }

                    printHex(context.connectionId, direction, buffer, length);
                    delayBeforeForward();
                    try {
                        output.write(buffer, 0, length);
                        output.flush();
                    } catch (IOException e) {
                        logAbnormalDisconnect(destinationPeer, destinationSocket, "写入失败", e);
                        throw e;
                    }
                }

                shutdownOutput(destinationSocket);
                log("[连接-%d][%s] 数据流结束", context.connectionId, direction);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                closeBothSockets();
            } catch (IOException e) {
                closeBothSockets();
            } finally {
                finished.countDown();
            }
        }

        private void delayBeforeForward() throws InterruptedException {
            long delayMillis = outbound ? outboundDelayMillis : inboundDelayMillis;
            if (delayMillis > 0) {
                log("[连接-%d][%s] 模拟延迟 %dms",
                        context.connectionId, direction, delayMillis);
                Thread.sleep(delayMillis);
            }
        }

        private void logAbnormalDisconnect(Peer peer,
                                           Socket socket,
                                           String operation,
                                           IOException exception) {
            if (running.get() && !socket.isClosed()) {
                context.logPeerDisconnected(
                        peer,
                        socket,
                        operation + "：" + exception.getMessage());
            }
        }

        private void closeBothSockets() {
            closeSocket(sourceSocket);
            closeSocket(destinationSocket);
        }
    }

    /** 停止监听并关闭全部活动连接。 */
    public void stop() {
        boolean wasRunning = running.getAndSet(false);
        closeServerSocket(serverSocket);
        activeSockets.forEach(TcpProxyServer::closeSocket);
        activeSockets.clear();
        workerPool.shutdownNow();

        if (wasRunning) {
            try {
                workerPool.awaitTermination(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            log("TCP 代理已停止");
        }
    }

    @Override
    public void close() {
        stop();
    }

    private static void configureSocket(Socket socket) throws SocketException {
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
    }

    private static void shutdownOutput(Socket socket) {
        if (socket == null || socket.isClosed() || socket.isOutputShutdown()) {
            return;
        }
        try {
            socket.shutdownOutput();
        } catch (IOException ignored) {
            // 对端可能已经同时关闭连接。
        }
    }

    private static void closeServerSocket(ServerSocket socket) {
        if (socket == null || socket.isClosed()) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // 停止服务时忽略重复关闭异常。
        }
    }

    private static void closeSocket(Socket socket) {
        if (socket == null || socket.isClosed()) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // 停止服务时忽略重复关闭异常。
        }
    }

    private static void printHex(long connectionId,
                                 String direction,
                                 byte[] data,
                                 int length) {
        StringBuilder dump = new StringBuilder();
        dump.append(String.format("[连接-%d][%s] %d bytes%n", connectionId, direction, length));

        for (int offset = 0; offset < length; offset += 16) {
            int lineLength = Math.min(16, length - offset);
            dump.append(String.format("%04X  ", offset));

            for (int i = 0; i < 16; i++) {
                if (i < lineLength) {
                    dump.append(String.format("%02X ", data[offset + i] & 0xFF));
                } else {
                    dump.append("   ");
                }
                if (i == 7) {
                    dump.append(' ');
                }
            }

            dump.append(" |");
            for (int i = 0; i < lineLength; i++) {
                int value = data[offset + i] & 0xFF;
                dump.append(value >= 32 && value <= 126 ? (char) value : '.');
            }
            dump.append('|').append(System.lineSeparator());
        }

        synchronized (LOG_LOCK) {
            System.out.print(dump);
        }
    }

    private static void log(String format, Object... args) {
        synchronized (LOG_LOCK) {
            System.out.printf(format + "%n", args);
        }
    }

    private static void logError(String format, Object... args) {
        synchronized (LOG_LOCK) {
            System.err.printf(format + "%n", args);
        }
    }

    private static void validatePort(int port, String fieldName) {
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException(fieldName + "必须在 1~65535 之间");
        }
    }

    private static void validateDelay(long delayMillis, String fieldName) {
        if (delayMillis < 0) {
            throw new IllegalArgumentException(fieldName + "不能小于 0");
        }
    }

    private enum Peer {
        AGENT("Agent"),
        TARGET("目标服务");

        private final String displayName;

        Peer(String displayName) {
            this.displayName = displayName;
        }
    }

    /** 每个代理会话独立记录两端状态，避免连接级联关闭时重复打印断开日志。 */
    private static final class ConnectionContext {
        private final long connectionId;
        private final AtomicBoolean agentDisconnectLogged = new AtomicBoolean(false);
        private final AtomicBoolean targetDisconnectLogged = new AtomicBoolean(false);

        private ConnectionContext(long connectionId) {
            this.connectionId = connectionId;
        }

        private void logPeerDisconnected(Peer peer, Socket socket, String reason) {
            AtomicBoolean logged = peer == Peer.AGENT
                    ? agentDisconnectLogged
                    : targetDisconnectLogged;
            if (!logged.compareAndSet(false, true)) {
                return;
            }

            log("[连接-%d][TCP断开] 对方 %s 已断开：remote=%s，原因=%s",
                    connectionId,
                    peer.displayName,
                    socket.getRemoteSocketAddress(),
                    reason);
        }
    }

    private static final class ProxyThreadFactory implements ThreadFactory {
        private final AtomicLong sequence = new AtomicLong(0);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "tcp-proxy-" + sequence.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        }
    }

    /** 启动参数：targetHost targetPort agentPort [outboundDelayMs] [inboundDelayMs]。 */
    public static void main(String[] args) {
//        if (args.length < 3 || args.length > 5) {
//            System.out.println("用法: java org.example.TcpProxyServer "
//                    + "<目标IP> <目标端口> <Agent监听端口> [出访延迟ms] [入访延迟ms]");
//            System.out.println("示例: java org.example.TcpProxyServer 127.0.0.1 12100 12111 0 30000");
//            return;
//        }
        args = new String[]{"192.168.70.85", "9210","8888","5","0"};

        try {
            String targetHost = args[0];
            int targetPort = Integer.parseInt(args[1]);
            int agentPort = Integer.parseInt(args[2]);
            long outboundDelayMillis = args.length >= 4 ? Long.parseLong(args[3]) : 0;
            long inboundDelayMillis = args.length >= 5 ? Long.parseLong(args[4]) : 0;

            TcpProxyServer proxy = new TcpProxyServer(
                    targetHost,
                    targetPort,
                    agentPort,
                    outboundDelayMillis,
                    inboundDelayMillis);
            Runtime.getRuntime().addShutdownHook(new Thread(proxy::stop, "tcp-proxy-shutdown"));
            proxy.start();
        } catch (NumberFormatException e) {
            System.err.println("端口和延迟参数必须是整数：" + e.getMessage());
        } catch (Exception e) {
            System.err.println("TCP 代理运行失败：" + e.getMessage());
        }
    }
}
