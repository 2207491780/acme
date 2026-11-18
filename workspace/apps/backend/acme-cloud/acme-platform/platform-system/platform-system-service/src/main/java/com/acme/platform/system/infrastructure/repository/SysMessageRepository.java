package com.acme.platform.system.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepository;

import com.acme.platform.system.domain.SysMessage;

import java.util.List;

/** 站内信仓库（租户归属，租户列自动过滤；收件箱按 recipient 语义）。 */
public interface SysMessageRepository extends FlexRepository<SysMessage> {

    /** 扇出/单发批量插入（确认记录③写放大：BaseMapper.insertBatch 单批提交）。 */
    void insertAll(List<SysMessage> messages);

    /** 未读数（计数用独立无排序 QueryWrapper，playbook 口径 5）。 */
    long countUnreadByRecipient(Long recipientId);

    /** 全部已读：当前收件人未读消息批量置已读（read_at 记录首批标记时间），返回受影响行数。 */
    int markAllReadByRecipient(Long recipientId, java.time.Instant readAt);
}
