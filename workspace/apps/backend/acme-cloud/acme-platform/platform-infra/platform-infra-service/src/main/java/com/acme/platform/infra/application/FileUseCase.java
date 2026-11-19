package com.acme.platform.infra.application;

import com.acme.common.context.TenantContext;
import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import com.acme.common.core.BizException;
import com.acme.framework.storage.config.StorageProperties;
import com.acme.framework.storage.support.ObjectKey;
import com.acme.framework.storage.support.ObjectStorage;
import com.acme.framework.storage.support.StorageObject;
import com.acme.framework.tenant.support.TenantError;
import com.acme.platform.infra.InfraError;
import com.acme.platform.infra.api.dto.DirectUploadPreparationView;
import com.acme.platform.infra.api.dto.FileView;
import com.acme.platform.infra.domain.SysFile;
import com.acme.platform.infra.infrastructure.redis.UploadTicketStore;
import com.acme.platform.infra.infrastructure.repository.SysFileRepository;
import com.mybatisflex.core.query.QueryColumn;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 文件服务用例（REQ-20261114-071607 §API 文件服务）：上传（白名单 + 大小上限 → 落对象 + sys_file）、
 * 分页（bizType/bizId/原名模糊）、元数据、下载、幂等软删。
 * 对象键统一 namespace {@link #NAMESPACE} + 日期段 + 唯一名（framework-storage ObjectKey 口径）。
 *
 * <p>REQ-20261117-045754 增补：对象键带租户段（{@code files/{tenantCode}/yyyy/MM/dd/uuid.ext}，
 * 服务端经 {@link TenantContext} 组装，客户端不可传，确认②）＋ 租户配额（确认③，multipart 与直传
 * preparation 同检）＋ 图片语义（确认④，multipart 与直传 completion 两条路执行）＋ 直传三段
 * （preparation / 票据消费 / completion，确认①⑥；completion 幂等——同 objectKey 未删行直接返回）。
 * {@link #objectKeyOf(String)} 兼容 5 段（存量行）与 6 段（租户段新格式）。</p>
 */
@Service
public class FileUseCase {

    /** 对象键命名空间（platform-infra 文件服务统一前缀）。 */
    public static final String NAMESPACE = "files";

    /** LOCAL 直传票据有效期（确认①：5min）。 */
    static final Duration TICKET_TTL = Duration.ofMinutes(5);

    /** LOCAL 票据直传端点（同源 PUT，走既有认证面；MINIO/OSS 模式客户端直 PUT 预签名 URL）。 */
    private static final String TICKET_UPLOAD_URL = "/admin/v1/infra/files/direct-uploads/%s";

    private final SysFileRepository repository;
    private final ObjectStorage storage;
    private final StorageProperties storageProperties;
    private final UploadTicketStore ticketStore;

    public FileUseCase(SysFileRepository repository, ObjectStorage storage,
                       StorageProperties storageProperties, UploadTicketStore ticketStore) {
        this.repository = repository;
        this.storage = storage;
        this.storageProperties = storageProperties;
        this.ticketStore = ticketStore;
    }

    /**
     * 上传（multipart）：大小上限（FILE_SIZE_EXCEEDED）→ 类型白名单（FILE_TYPE_REJECTED）→
     * 租户配额（QUOTA_EXCEEDED）→ 图片语义（IMAGE_INVALID）→ 落对象（租户段键）+ 记录。
     */
    public FileView upload(MultipartFile file, String bizType, String bizId, String operatorId) {
        if (file == null || file.isEmpty()) {
            throw new BizException(InfraError.FILE_TYPE_REJECTED, "上传文件不能为空");
        }
        validateUploadBasics(file.getOriginalFilename(), file.getContentType(), file.getSize());
        // 图片语义需复用字节流（确认④）：先读内存再校验再写（max-size 10MB 上限内可接受）
        byte[] payload = readAll(file);
        validateImageIfNeed(file.getContentType(), payload);
        ObjectKey key = newObjectKey(file.getOriginalFilename());
        storage.put(new StorageObject(key, new ByteArrayInputStream(payload),
                payload.length, file.getContentType()));
        SysFile entity = new SysFile(storageProperties.getBucket(), key.path(),
                file.getOriginalFilename(), (long) payload.length, file.getContentType(),
                blankToNull(bizType), blankToNull(bizId));
        entity.setUploadedBy(operatorId);
        repository.save(entity);
        return view(entity, storage.url(key));
    }

    // ---------- 直传三段（REQ-20261117-045754 确认①） ----------

    /**
     * 直传准备：校验链（空/大小/白名单/配额；图片语义在 completion 校验真实字节）→
     * 带租户段 objectKey → LOCAL 签发一次性票据（PUT 票据端点）或 MINIO/OSS 预签名 PUT URL。
     */
    public DirectUploadPreparationView prepareDirectUpload(String fileName, String contentType, long size,
                                                           String bizType, String bizId, String operatorId) {
        if (fileName == null || fileName.isBlank() || size <= 0) {
            throw new BizException(InfraError.FILE_TYPE_REJECTED, "上传文件不能为空");
        }
        validateUploadBasics(fileName, contentType, size);
        ObjectKey key = newObjectKey(fileName);
        Map<String, String> headers = blankToNull(contentType) == null
                ? Map.of() : Map.of("Content-Type", contentType);
        if (storageProperties.getType() == StorageProperties.Type.LOCAL) {
            String ticket = ticketStore.create(new UploadTicketStore.Ticket(key.path(), fileName,
                    contentType, size, blankToNull(bizType), blankToNull(bizId),
                    requireTenantId(), operatorId), TICKET_TTL);
            return new DirectUploadPreparationView(key.path(), TICKET_UPLOAD_URL.formatted(ticket),
                    "PUT", headers, TICKET_TTL.getSeconds(), ticket);
        }
        // MINIO/OSS：预签名 PUT URL（Content-Type 头随签名，客户端须原样携带 headers）
        return new DirectUploadPreparationView(key.path(), storage.presignUpload(key, contentType, size),
                "PUT", headers, Math.max(storageProperties.getPresignTtl().getSeconds(), 1), null);
    }

    /**
     * 票据消费（LOCAL 二段 PUT）：GET+DEL 原子单次消费（不存在/过期/已消费统一 TICKET_INVALID）；
     * 票据绑定租户与操作者（防跨租户替传，确认⑥）。
     */
    public UploadTicketStore.Ticket consumeTicket(String ticket, String operatorId) {
        UploadTicketStore.Ticket payload = ticketStore.take(ticket)
                .orElseThrow(() -> new BizException(InfraError.TICKET_INVALID));
        String tenantId = requireTenantId();
        if (!tenantId.equals(payload.tenantId()) || !operatorIdMatches(payload.operatorId(), operatorId)) {
            throw new BizException(InfraError.TICKET_INVALID);
        }
        return payload;
    }

    /**
     * 票据直传写对象（LOCAL 二段）：原始字节体落对象；Content-Type 头须与票据登记一致
     * （确认①「Content-Type 头须与 preparation 一致」，不一致按票据失效拒绝）。
     */
    public void putDirectUpload(UploadTicketStore.Ticket ticket, InputStream body,
                                long contentLength, String requestContentType) {
        String registered = ticket.contentType();
        boolean contentTypeMismatch = registered != null && !registered.isBlank()
                && (requestContentType == null || !registered.equalsIgnoreCase(requestContentType.trim()));
        if (contentTypeMismatch) {
            throw new BizException(InfraError.TICKET_INVALID, "Content-Type 与直传登记不一致");
        }
        storage.put(new StorageObject(objectKeyOf(ticket.objectKey()), body, contentLength, registered));
    }

    /**
     * 直传完成：objectKey 归属当前租户（6 段租户段比对，不匹配租户拒绝语义）→ 幂等（同键未删行
     * 直接返回既有 view，确认⑥）→ 对象存在（STORAGE_OBJECT_MISSING 404 语义）
     * → size 一致（容差 0，SIZE_MISMATCH）→ 图片语义（确认④）→ 落 sys_file。
     */
    public FileView completeDirectUpload(String objectKey, String fileName, String contentType,
                                         long size, String bizType, String bizId, String operatorId) {
        requireTenantSegment(objectKey);
        Optional<SysFile> existing = repository.findActiveByObjectKey(objectKey);
        if (existing.isPresent()) {
            // 幂等：同 objectKey 未删行命中直接返回（不重复落库，确认⑥）
            return view(existing.get());
        }
        ObjectKey key = objectKeyOf(objectKey);
        if (!storage.exists(key)) {
            throw new BizException(InfraError.STORAGE_OBJECT_MISSING);
        }
        byte[] payload = readStored(key, size);
        if (payload.length != size) {
            throw new BizException(InfraError.SIZE_MISMATCH);
        }
        validateImageIfNeed(contentType, payload);
        SysFile entity = new SysFile(storageProperties.getBucket(), key.path(), fileName,
                size, contentType, blankToNull(bizType), blankToNull(bizId));
        entity.setUploadedBy(operatorId);
        repository.save(entity);
        return view(entity, storage.url(key));
    }

    // ---------- 查询 / 删除 ----------

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

    // ---------- 校验链 ----------

    /** 共用校验链（multipart 与直传 preparation 同检，确认③口径）：大小上限 → 类型白名单 → 租户配额。 */
    private void validateUploadBasics(String fileName, String contentType, long size) {
        if (size > storageProperties.getUpload().getMaxSize().toBytes()) {
            throw new BizException(InfraError.FILE_SIZE_EXCEEDED);
        }
        if (!typeAllowed(fileName, contentType)) {
            throw new BizException(InfraError.FILE_TYPE_REJECTED);
        }
        checkQuota(size);
    }

    /** 租户配额（确认③）：未删行 size 总和 + 本次 ≤ quota.defaultBytes（0=不限）；并发上传下为近似护栏。 */
    private void checkQuota(long incomingSize) {
        long quota = storageProperties.getQuota().getDefaultBytes();
        if (quota <= 0) {
            return;
        }
        if (repository.sumActiveSize() + incomingSize > quota) {
            throw new BizException(InfraError.STORAGE_QUOTA_EXCEEDED);
        }
    }

    /** 类型白名单（扩展名或 contentType 命中其一即放行，REQ-20261114-071607 AC-2 裁决⑦双口径）。 */
    private boolean typeAllowed(String fileName, String contentType) {
        Set<String> allowed = new HashSet<>();
        for (String type : storageProperties.getUpload().getAllowedContentTypes()) {
            if (type != null && !type.isBlank()) {
                allowed.add(type.trim().toLowerCase(Locale.ROOT));
            }
        }
        if (allowed.isEmpty()) {
            return true;
        }
        if (fileName != null) {
            int dot = fileName.lastIndexOf('.');
            if (dot >= 0 && dot < fileName.length() - 1
                    && allowed.contains(fileName.substring(dot + 1).toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
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

    /**
     * 图片语义（确认④）：contentType 以 image/ 开头时经 ImageIO 校验「可解码 ＋ 宽高 ≤
     * upload.max-image-px」；不可解码/超尺寸 IMAGE_INVALID；非 image 直接放行。
     */
    private void validateImageIfNeed(String contentType, byte[] payload) {
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            return;
        }
        BufferedImage image;
        try (InputStream in = new ByteArrayInputStream(payload)) {
            image = ImageIO.read(in);
        } catch (IOException ex) {
            throw new BizException(InfraError.IMAGE_INVALID, "图片流读取失败");
        }
        if (image == null) {
            throw new BizException(InfraError.IMAGE_INVALID, "图片无法解码");
        }
        int maxPx = storageProperties.getUpload().getMaxImagePx();
        if (image.getWidth() > maxPx || image.getHeight() > maxPx) {
            throw new BizException(InfraError.IMAGE_INVALID, "图片像素超出上限");
        }
    }

    // ---------- 对象键 ----------

    /** 新对象键（租户段新格式，确认②）：files/{tenantId}/yyyy/MM/dd/uuid.ext，租户段服务端组装。 */
    private ObjectKey newObjectKey(String originalName) {
        return ObjectKey.of(NAMESPACE + "/" + requireTenantId(), uniqueName(originalName));
    }

    /** 当前租户 id（TenantContext；缺上下文显式失败，不产生无租户键脏行）。 */
    private static String requireTenantId() {
        TenantContext tenant = TenantContext.get();
        if (tenant == null || tenant.tenantId() == null || tenant.tenantId().isBlank()) {
            throw new BizException(TenantError.TENANT_REQUIRED);
        }
        return tenant.tenantId().trim();
    }

    /**
     * 直传 completion 租户归属校验（确认②⑥）：objectKey 恒为新格式 6 段
     * {@code files/{tenantCode}/yyyy/MM/dd/name} 且租户段 == 当前租户；5 段存量键 / 异租户键 /
     * 异 namespace 一律拒绝（TENANT_MISMATCH 403 语义）。
     */
    private void requireTenantSegment(String objectKey) {
        String tenantId = requireTenantId();
        String[] segments = objectKey == null ? new String[0] : objectKey.split("/");
        if (segments.length != 6 || !NAMESPACE.equals(segments[0]) || !tenantId.equals(segments[1])) {
            throw new BizException(TenantError.TENANT_MISMATCH);
        }
    }

    /**
     * 存储路径 → ObjectKey（5 段：namespace/yyyy/MM/dd/name 存量口径；6 段：租户段新格式；
     * 其余形态拒绝）。6 段解析回 namespace {@code files/{tenantCode}}，path() 可逆。
     */
    public static ObjectKey objectKeyOf(String path) {
        if (path == null || path.isBlank()) {
            throw new BizException(InfraError.FILE_NOT_FOUND);
        }
        String[] segments = path.split("/");
        if (segments.length != 5 && segments.length != 6) {
            throw new IllegalStateException("对象键形态非法（非本服务生成口径）: " + path);
        }
        LocalDate date = LocalDate.of(Integer.parseInt(segments[segments.length - 4]),
                Integer.parseInt(segments[segments.length - 3]),
                Integer.parseInt(segments[segments.length - 2]));
        String namespace = segments.length == 6 ? segments[0] + "/" + segments[1] : segments[0];
        return ObjectKey.of(namespace, segments[segments.length - 1], date);
    }

    // ---------- 支撑 ----------

    private static byte[] readAll(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException("读取上传流失败: " + file.getOriginalFilename(), ex);
        }
    }

    /** 读存量对象字节（completion size 校验面；多读 1 字节防无界内存）。 */
    private byte[] readStored(ObjectKey key, long expectedSize) {
        try (InputStream in = storage.get(key)) {
            return in.readNBytes((int) Math.min(Math.max(expectedSize, 0) + 1, Integer.MAX_VALUE));
        } catch (IOException ex) {
            throw new UncheckedIOException("读取对象失败: " + key.path(), ex);
        }
    }

    private static boolean operatorIdMatches(String ticketOperator, String requestOperator) {
        // 操作者绑定：preparation 与 PUT 须同一操作者（上下文缺失时按空串归一比对）
        String left = ticketOperator == null ? "" : ticketOperator.trim();
        String right = requestOperator == null ? "" : requestOperator.trim();
        return left.equals(right);
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
}
