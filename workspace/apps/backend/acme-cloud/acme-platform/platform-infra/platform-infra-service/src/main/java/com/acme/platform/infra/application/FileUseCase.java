package com.acme.platform.infra.application;

import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import com.acme.common.core.BizException;
import com.acme.framework.storage.config.StorageProperties;
import com.acme.framework.storage.support.ObjectKey;
import com.acme.framework.storage.support.ObjectStorage;
import com.acme.framework.storage.support.StorageObject;
import com.acme.platform.infra.InfraError;
import com.acme.platform.infra.api.dto.FileView;
import com.acme.platform.infra.domain.SysFile;
import com.acme.platform.infra.infrastructure.repository.SysFileRepository;
import com.mybatisflex.core.query.QueryColumn;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 文件服务用例（REQ-20261114-071607 §API 文件服务）：上传（白名单 + 大小上限 → 落对象 + sys_file）、
 * 分页（bizType/bizId/原名模糊）、元数据、下载、幂等软删。
 * 对象键统一 namespace {@link #NAMESPACE} + 日期段 + 唯一名（framework-storage ObjectKey 口径）。
 */
@Service
public class FileUseCase {

    /** 对象键命名空间（platform-infra 文件服务统一前缀）。 */
    public static final String NAMESPACE = "files";

    private final SysFileRepository repository;
    private final ObjectStorage storage;
    private final StorageProperties storageProperties;

    public FileUseCase(SysFileRepository repository, ObjectStorage storage, StorageProperties storageProperties) {
        this.repository = repository;
        this.storage = storage;
        this.storageProperties = storageProperties;
    }

    /** 上传：大小上限（FILE_SIZE_EXCEEDED）→ 类型白名单（FILE_TYPE_REJECTED）→ 落对象 + 记录。 */
    public FileView upload(MultipartFile file, String bizType, String bizId, String operatorId) {
        if (file == null || file.isEmpty()) {
            throw new BizException(InfraError.FILE_TYPE_REJECTED, "上传文件不能为空");
        }
        if (file.getSize() > storageProperties.getUpload().getMaxSize().toBytes()) {
            throw new BizException(InfraError.FILE_SIZE_EXCEEDED);
        }
        if (!typeAllowed(file)) {
            throw new BizException(InfraError.FILE_TYPE_REJECTED);
        }
        ObjectKey key = ObjectKey.of(NAMESPACE, uniqueName(file.getOriginalFilename()));
        try (InputStream in = file.getInputStream()) {
            storage.put(new StorageObject(key, in, file.getSize(), file.getContentType()));
        } catch (IOException ex) {
            throw new UncheckedIOException("读取上传流失败: " + file.getOriginalFilename(), ex);
        }
        SysFile entity = new SysFile(storageProperties.getBucket(), key.path(),
                file.getOriginalFilename(), file.getSize(), file.getContentType(),
                blankToNull(bizType), blankToNull(bizId));
        entity.setUploadedBy(operatorId);
        repository.save(entity);
        return view(entity, storage.url(key));
    }

    /** 分页（可选 bizType/bizId 过滤 + 原名模糊；created_at 倒序稳定排序）。 */
    public PageResult<SysFile> page(PageQuery query, String bizType, String bizId, String keyword) {
        QueryWrapper wrapper = QueryWrapper.create();
        if (bizType != null && !bizType.isBlank()) {
            wrapper.where(new QueryColumn("biz_type").eq(bizType.trim()));
        }
        if (bizId != null && !bizId.isBlank()) {
            wrapper.and(new QueryColumn("biz_id").eq(bizId.trim()));
        }
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(new QueryColumn("original_name").like(keyword.trim()));
        }
        wrapper.orderBy(new QueryColumn("created_at"), false).orderBy(new QueryColumn("id"), false);
        return repository.findAll(wrapper, query.page(), query.size());
    }

    /** 元数据（不存在 FILE_NOT_FOUND）。 */
    public SysFile get(Long id) {
        return repository.findById(id).orElseThrow(() -> new BizException(InfraError.FILE_NOT_FOUND));
    }

    /** 文件内容（流式下载载荷：记录 + 对象流）。 */
    public record FileContent(SysFile file, InputStream stream) {
    }

    /** 打开对象内容流（记录不存在 FILE_NOT_FOUND；对象缺失走门面统一 OBJECT_NOT_FOUND 语义）。 */
    public FileContent openContent(Long id) {
        SysFile file = get(id);
        return new FileContent(file, storage.get(objectKeyOf(file.getObjectKey())));
    }

    /** 软删 + 对象移除（幂等：记录已删或对象不存在均成功，AC-4）。 */
    public void delete(Long id) {
        SysFile file = repository.findById(id).orElse(null);
        if (file == null) {
            // 记录不存在或已软删：幂等成功（AC-4）
            return;
        }
        storage.remove(objectKeyOf(file.getObjectKey()));
        repository.deleteById(id);
    }

    /** 实体 → B 端视图（url 经存储门面解析）。 */
    public FileView view(SysFile entity) {
        return view(entity, storage.url(objectKeyOf(entity.getObjectKey())));
    }

    private FileView view(SysFile entity, String url) {
        return new FileView(String.valueOf(entity.getId()), entity.getOriginalName(), entity.getSize(),
                entity.getContentType(), url, entity.getBizType(), entity.getBizId(), entity.getCreatedAt());
    }

    /** 类型白名单（扩展名或 multipart contentType 命中其一即放行，REQ-20261114-071607 AC-2 裁决⑦双口径）。 */
    private boolean typeAllowed(MultipartFile file) {
        Set<String> allowed = new HashSet<>();
        for (String type : storageProperties.getUpload().getAllowedContentTypes()) {
            if (type != null && !type.isBlank()) {
                allowed.add(type.trim().toLowerCase(Locale.ROOT));
            }
        }
        if (allowed.isEmpty()) {
            return true;
        }
        String name = file.getOriginalFilename();
        if (name != null) {
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && dot < name.length() - 1
                    && allowed.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        String contentType = file.getContentType();
        if (contentType == null) {
            return false;
        }
        String normalized = contentType.toLowerCase(Locale.ROOT);
        // 白名单 token（xlsx/pdf/...）与 MIME 双口径兼容：application/pdf 命中 pdf、text/csv 命中 csv
        for (String entry : allowed) {
            if (normalized.contains(entry)) {
                return true;
            }
        }
        return false;
    }

    /** 唯一对象名（保留原扩展名，便于内容识别；原名不含可判定扩展名时保留原名拼唯一段）。 */
    private static String uniqueName(String originalName) {
        String unique = UUID.randomUUID().toString().replace("-", "");
        if (originalName == null || originalName.isBlank()) {
            return unique;
        }
        int dot = originalName.lastIndexOf('.');
        if (dot > 0 && dot < originalName.length() - 1) {
            return unique + originalName.substring(dot).toLowerCase(Locale.ROOT);
        }
        return unique + "-" + originalName;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 存储路径 → ObjectKey（namespace/yyyy/MM/dd/name；本服务生成键恒为 5 段，异常形态拒绝）。 */
    public static ObjectKey objectKeyOf(String path) {
        if (path == null || path.isBlank()) {
            throw new BizException(InfraError.FILE_NOT_FOUND);
        }
        String[] segments = path.split("/");
        if (segments.length != 5) {
            throw new IllegalStateException("对象键形态非法（非本服务生成口径）: " + path);
        }
        LocalDate date = LocalDate.of(Integer.parseInt(segments[1]),
                Integer.parseInt(segments[2]), Integer.parseInt(segments[3]));
        return ObjectKey.of(segments[0], segments[4], date);
    }
}
