package com.acme.common.test;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 零 Docker 原生嵌入式 Redis 底座回归测试：
 * 校验原生端口监听、Lua EVAL + 多字段 HSET（对齐 RedisTokenBucket）以及 FLUSHALL 毫秒级重置。
 */
class EmbeddedRedisSupportTest {

    @Test
    void should_startSharedRedisAndSupportMultiFieldHsetLuaAndFlushAll() throws Exception {
        EmbeddedRedisSupport redis = EmbeddedRedisSupport.shared();
        assertThat(EmbeddedRedisSupport.shared()).isSameAs(redis);
        assertThat(redis.port()).isPositive();

        // 写入键并校验多字段 HSET Lua 脚本（RedisTokenBucket 依赖 Redis >= 4.0 语法）
        String lua = "redis.call('HSET', KEYS[1], 't', ARGV[1], 'ts', ARGV[2]); return redis.call('HGET', KEYS[1], 't')";
        String evalResp = rawCommand(redis.host(), redis.port(), "EVAL", lua, "1", "acme:test:bucket", "42", "1000");
        assertThat(evalResp).contains("42");

        // flushAll 清理后键消失
        redis.flushAll();
        String getResp = rawCommand(redis.host(), redis.port(), "HGET", "acme:test:bucket", "t");
        assertThat(getResp).startsWith("$-1");
    }

    private static String rawCommand(String host, int port, String... args) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append('*').append(args.length).append("\r\n");
        for (String arg : args) {
            byte[] bytes = arg.getBytes(StandardCharsets.UTF_8);
            sb.append('$').append(bytes.length).append("\r\n").append(arg).append("\r\n");
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 2000);
            socket.setSoTimeout(2000);
            OutputStream out = socket.getOutputStream();
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
            InputStream in = socket.getInputStream();
            byte[] buf = new byte[256];
            int n = in.read(buf);
            return n > 0 ? new String(buf, 0, n, StandardCharsets.UTF_8) : "";
        }
    }
}
