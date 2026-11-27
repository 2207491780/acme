package com.acme.common.test;

import redis.embedded.RedisServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 零 Docker 原生嵌入式 Redis 测试底座（单 JVM 共享实例 + &lt;1ms {@code FLUSHALL} 重置）。
 *
 * <p>同 JVM 内仅启动 1 个本地 Redis 进程（禁用 RDB 落盘，支持 Lua {@code EVAL}、多字段 {@code HSET}
 * 与 {@code PUB/SUB}），JVM 退出时由 ShutdownHook 自动停止。</p>
 */
public final class EmbeddedRedisSupport {

    private static volatile EmbeddedRedisSupport instance;
    private static final Object INIT_LOCK = new Object();

    private final RedisServer server;
    private final int port;

    private EmbeddedRedisSupport(RedisServer server, int port) {
        this.server = server;
        this.port = port;
    }

    /** 获取当前 JVM 单例嵌入式 Redis 实例（首次调用自动在随机空闲端口启动）。 */
    public static EmbeddedRedisSupport shared() {
        EmbeddedRedisSupport current = instance;
        if (current != null) {
            return current;
        }
        synchronized (INIT_LOCK) {
            if (instance == null) {
                instance = startNew();
            }
            return instance;
        }
    }

    private static EmbeddedRedisSupport startNew() {
        int freePort = findFreePort();
        try {
            RedisServer redis = RedisServer.newRedisServer()
                    .port(freePort)
                    .setting("maxmemory 128M")
                    .setting("save \"\"")
                    .setting("appendonly no")
                    .build();
            redis.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    redis.stop();
                } catch (Exception ignored) {
                    // JVM 退出清理忽略停止异常
                }
            }, "acme-embedded-redis-shutdown"));
            return new EmbeddedRedisSupport(redis, freePort);
        } catch (IOException ex) {
            throw new UncheckedIOException("启动嵌入式 Redis 失败（port=" + freePort + "）", ex);
        }
    }

    public String host() {
        return "127.0.0.1";
    }

    public int port() {
        return port;
    }

    /** 清空全部键（原生 RESP 命令直连，&lt;1ms，无客户端库依赖）。 */
    public void flushAll() {
        sendCommand("FLUSHALL");
    }

    /** 发送单条无参 RESP 命令并校验 {@code +OK} 响应。 */
    void sendCommand(String cmd) {
        byte[] payload = ("*1\r\n$" + cmd.length() + "\r\n" + cmd + "\r\n").getBytes(StandardCharsets.UTF_8);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host(), port), 2000);
            socket.setSoTimeout(2000);
            OutputStream out = socket.getOutputStream();
            out.write(payload);
            out.flush();
            InputStream in = socket.getInputStream();
            byte[] buf = new byte[64];
            int read = in.read(buf);
            if (read <= 0 || buf[0] != '+') {
                String resp = read > 0 ? new String(buf, 0, read, StandardCharsets.UTF_8).trim() : "<eof>";
                throw new IllegalStateException("嵌入式 Redis 执行 " + cmd + " 失败: " + resp);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("连接嵌入式 Redis 执行 " + cmd + " 失败", ex);
        }
    }

    private static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new UncheckedIOException("分配空闲端口失败", ex);
        }
    }
}
