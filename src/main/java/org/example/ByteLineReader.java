package org.example;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/**
 * 基于FileChannel的按行字节数组读取器。
 * 使用ByteBuffer批量读取，直接操作字节不做编码转换，
 * 适用于需要按字节位置截取字段的定宽文件解析场景。
 */
public class ByteLineReader implements Closeable {

    private static final int BUFFER_SIZE = 64 * 1024; // 64KB

    private final FileChannel channel;
    private final ByteBuffer buffer;
    private boolean bomSkipped;
    private boolean eofReached;

    // 跨缓冲区的行数据暂存
    private byte[] pending;
    private int pendingLen;

    public ByteLineReader(Path path) throws IOException {
        this.channel = FileChannel.open(path, StandardOpenOption.READ);
        this.buffer = ByteBuffer.allocate(BUFFER_SIZE);
        this.buffer.limit(0); // 初始为空
        this.bomSkipped = false;
        this.eofReached = false;
        this.pending = new byte[BUFFER_SIZE];
        this.pendingLen = 0;
    }

    /**
     * 读取下一行的字节数组（不包含换行符）。
     * 自动去除行尾的\r，文件首行自动跳过UTF-8 BOM。
     *
     * @return 行的字节数组，到达文件末尾时返回null
     */
    public byte[] readLine() throws IOException {
        while (true) {
            if (buffer.hasRemaining()) {
                byte[] array = buffer.array();
                int start = buffer.position();
                int limit = buffer.limit();

                // 在缓冲区中扫描 \n
                for (int i = start; i < limit; i++) {
                    if (array[i] == '\n') {
                        int lineLen = i - start;
                        byte[] result;
                        if (pendingLen > 0) {
                            // 拼接暂存数据
                            result = new byte[pendingLen + lineLen];
                            System.arraycopy(pending, 0, result, 0, pendingLen);
                            System.arraycopy(array, start, result, pendingLen, lineLen);
                            pendingLen = 0;
                        } else {
                            result = Arrays.copyOfRange(array, start, i);
                        }
                        buffer.position(i + 1);
                        return postProcess(result);
                    }
                }

                // 未找到 \n，将剩余字节暂存
                int remaining = limit - start;
                ensurePendingCapacity(remaining);
                System.arraycopy(array, start, pending, pendingLen, remaining);
                pendingLen += remaining;
                buffer.position(limit);
            }

            // 需要更多数据
            if (!refill()) {
                if (pendingLen > 0) {
                    byte[] result = Arrays.copyOf(pending, pendingLen);
                    pendingLen = 0;
                    return postProcess(result);
                }
                return null;
            }
        }
    }

    private void ensurePendingCapacity(int needed) {
        int newLen = pendingLen + needed;
        if (newLen > pending.length) {
            pending = Arrays.copyOf(pending, Math.max(newLen, pending.length * 2));
        }
    }

    private boolean refill() throws IOException {
        if (eofReached) {
            return false;
        }
        buffer.clear();
        int read = channel.read(buffer);
        if (read <= 0) {
            eofReached = true;
            buffer.limit(0);
            return false;
        }
        buffer.flip();
        return true;
    }

    private byte[] postProcess(byte[] result) {
        // 首行跳过UTF-8 BOM (EF BB BF)
        if (!bomSkipped) {
            bomSkipped = true;
            if (result.length >= 3
                    && (result[0] & 0xFF) == 0xEF
                    && (result[1] & 0xFF) == 0xBB
                    && (result[2] & 0xFF) == 0xBF) {
                result = Arrays.copyOfRange(result, 3, result.length);
            }
        }
        // 去除行尾 \r
        if (result.length > 0 && result[result.length - 1] == '\r') {
            result = Arrays.copyOf(result, result.length - 1);
        }
        return result;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
