package org.example;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Snowflake ID generator used by tests that need IDs compatible with Jupiter's
 * {@code SnowflakeIdWorker.nextIdStr()} layout.
 *
 * <p>The generated ID consists of 41 timestamp bits, 5 datacenter bits,
 * 5 worker bits, and 12 sequence bits. The custom epoch is 2015-01-01 UTC.</p>
 */
public final class SnowflakeIdWorkerUtil {

    private static final long EPOCH = 1420041600000L;
    private static final long WORKER_ID_BITS = 5L;
    private static final long DATACENTER_ID_BITS = 5L;
    private static final long SEQUENCE_BITS = 12L;

    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);
    private static final long MAX_DATACENTER_ID = ~(-1L << DATACENTER_ID_BITS);
    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final long DATACENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;
    private static final long TIMESTAMP_LEFT_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATACENTER_ID_BITS;
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);

    private static final Generator DEFAULT_GENERATOR = createDefaultGenerator();

    private SnowflakeIdWorkerUtil() {
    }

    /**
     * Generates a Snowflake ID as a decimal string.
     */
    public static String nextIdStr() {
        return DEFAULT_GENERATOR.nextIdStr();
    }

    /**
     * Generates a Snowflake ID as a {@code long}.
     */
    public static long nextId() {
        return DEFAULT_GENERATOR.nextId();
    }

    private static Generator createDefaultGenerator() {
        long datacenterId = resolveDatacenterId();
        long workerId = resolveWorkerId(datacenterId);
        return new Generator(workerId, datacenterId);
    }

    private static long resolveWorkerId(long datacenterId) {
        StringBuilder source = new StringBuilder().append(datacenterId);
        String runtimeName = ManagementFactory.getRuntimeMXBean().getName();
        if (runtimeName != null && !runtimeName.isEmpty()) {
            source.append(runtimeName.split("@")[0]);
        }
        return (source.toString().hashCode() & 0xffffL) % (MAX_WORKER_ID + 1);
    }

    private static long resolveDatacenterId() {
        long datacenterId = 0L;
        try {
            InetAddress localHost = InetAddress.getLocalHost();
            NetworkInterface networkInterface = NetworkInterface.getByInetAddress(localHost);
            if (networkInterface == null) {
                return 1L;
            }

            byte[] mac = networkInterface.getHardwareAddress();
            if (mac != null && mac.length >= 2) {
                datacenterId = ((0xffL & mac[mac.length - 1])
                        | (0xff00L & ((long) mac[mac.length - 2] << 8))) >> 6;
            }
        } catch (Exception ignored) {
            // Keep the same zero fallback behavior as Jupiter's default worker.
        }
        return datacenterId % (MAX_DATACENTER_ID + 1);
    }

    /**
     * Stateful, thread-safe Snowflake generator.
     */
    public static final class Generator {

        private final long workerId;
        private final long datacenterId;
        private long sequence;
        private long lastTimestamp = -1L;

        private Generator(long workerId, long datacenterId) {
            if (workerId < 0 || workerId > MAX_WORKER_ID) {
                throw new IllegalArgumentException("workerId must be between 0 and " + MAX_WORKER_ID);
            }
            if (datacenterId < 0 || datacenterId > MAX_DATACENTER_ID) {
                throw new IllegalArgumentException(
                        "datacenterId must be between 0 and " + MAX_DATACENTER_ID);
            }
            this.workerId = workerId;
            this.datacenterId = datacenterId;
        }

        public String nextIdStr() {
            return String.valueOf(nextId());
        }

        public synchronized long nextId() {
            long timestamp = currentTimeMillis();
            if (timestamp < lastTimestamp) {
                throw new IllegalStateException(
                        "Clock moved backwards by " + (lastTimestamp - timestamp) + " milliseconds");
            }

            if (timestamp == lastTimestamp) {
                sequence = (sequence + 1) & SEQUENCE_MASK;
                if (sequence == 0L) {
                    timestamp = waitUntilNextMillis(lastTimestamp);
                }
            } else {
                sequence = 0L;
            }

            lastTimestamp = timestamp;
            return ((timestamp - EPOCH) << TIMESTAMP_LEFT_SHIFT)
                    | (datacenterId << DATACENTER_ID_SHIFT)
                    | (workerId << WORKER_ID_SHIFT)
                    | sequence;
        }

        private long waitUntilNextMillis(long previousTimestamp) {
            long timestamp = currentTimeMillis();
            while (timestamp <= previousTimestamp) {
                Thread.onSpinWait();
                timestamp = currentTimeMillis();
            }
            return timestamp;
        }

        private long currentTimeMillis() {
            return System.currentTimeMillis();
        }
    }
}
