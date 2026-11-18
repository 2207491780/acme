package com.acme.framework.storage.internal;

import com.acme.common.core.BizException;
import com.acme.framework.storage.config.StorageProperties;
import com.acme.framework.storage.support.ObjectKey;
import com.acme.framework.storage.support.ObjectStorage;
import com.acme.framework.storage.support.StorageError;
import com.acme.framework.storage.support.StorageObject;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 本地盘适配器（REQ-20261114-071607：本地 dev 无对象存储中间件，生产经同一接口切 minio/oss）：
 * 对象按 {@link ObjectKey#path()} 落盘到 {@code acme.storage.local.root-dir}（目录结构 =
 * {@code root/namespace/yyyy/MM/dd/name}，AC-6）；url = {@code acme.storage.local.url-base} 拼接
 * ObjectKey 路径；remove 幂等（不存在视为成功，与五操作门面口径一致）。
 * 根目录缺失显式失败（SYS-15-0006，与 CREDENTIAL_MISSING 同「不静默空转」口径）。
 */
public class LocalObjectStorage implements ObjectStorage {

    private final Path root;
    private final String urlBase;

    public LocalObjectStorage(Path root, String urlBase) {
        this.root = root;
        this.urlBase = urlBase == null ? "" : urlBase.trim();
    }

    /** 按配置构造（根目录必配、启动即建目录 fail-fast；REQ-20261114-071607）。 */
    public static LocalObjectStorage connect(StorageProperties properties) {
        String rootDir = properties.getLocal().getRootDir();
        if (rootDir == null || rootDir.isBlank()) {
            throw new BizException(StorageError.LOCAL_ROOT_DIR_MISSING);
        }
        Path root = Path.of(rootDir.trim());
        try {
            Files.createDirectories(root);
        } catch (IOException ex) {
            throw new UncheckedIOException("本地存储根目录创建失败: " + root, ex);
        }
        return new LocalObjectStorage(root, properties.getLocal().getUrlBase());
    }

    @Override
    public void put(StorageObject object) {
        Path target = resolve(object.key());
        try {
            Files.createDirectories(target.getParent());
            Files.copy(object.content(), target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            throw new UncheckedIOException("对象写入失败: " + object.key().path(), ex);
        }
    }

    @Override
    public InputStream get(ObjectKey key) {
        Path target = resolve(key);
        if (!Files.isRegularFile(target)) {
            throw new BizException(StorageError.OBJECT_NOT_FOUND);
        }
        try {
            return new BufferedInputStream(Files.newInputStream(target));
        } catch (IOException ex) {
            throw new UncheckedIOException("对象读取失败: " + key.path(), ex);
        }
    }

    @Override
    public void remove(ObjectKey key) {
        Path target = resolve(key);
        try {
            Files.deleteIfExists(target);
        } catch (IOException ex) {
            throw new UncheckedIOException("对象删除失败: " + key.path(), ex);
        }
    }

    @Override
    public boolean exists(ObjectKey key) {
        return Files.isRegularFile(resolve(key));
    }

    @Override
    public String url(ObjectKey key) {
        String path = key.path();
        if (urlBase.isBlank()) {
            return path;
        }
        return urlBase.endsWith("/") ? urlBase + path : urlBase + "/" + path;
    }

    /** 落盘路径（防目录逃逸：归一化后必须仍在根目录内）。 */
    private Path resolve(ObjectKey key) {
        Path target = root.resolve(key.path()).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("对象键越界（目录逃逸拒绝）: " + key.path());
        }
        return target;
    }
}
