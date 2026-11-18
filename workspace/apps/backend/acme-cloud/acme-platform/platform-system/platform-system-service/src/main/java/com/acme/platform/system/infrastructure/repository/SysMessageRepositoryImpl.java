package com.acme.platform.system.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepositorySupport;

import com.acme.platform.system.domain.SysMessage;
import com.acme.platform.system.infrastructure.mapper.SysMessageMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/** 站内信仓库实现（MyBatis-Flex 底座；租户过滤由框架注入）。 */
@Repository
public class SysMessageRepositoryImpl extends FlexRepositorySupport<SysMessage>
        implements SysMessageRepository {

    public SysMessageRepositoryImpl(SysMessageMapper mapper) {
        super(mapper);
    }

    @Override
    public void insertAll(List<SysMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        mapper().insertBatch(messages);
    }

    @Override
    public long countUnreadByRecipient(Long recipientId) {
        return mapper().selectCountByQuery(unreadQuery(recipientId));
    }

    @Override
    public int markAllReadByRecipient(Long recipientId, Instant readAt) {
        return mapper().updateByQuery(SysMessage.readTemplate(readAt), true, unreadQuery(recipientId));
    }

    /** 收件人未读查询（独立无排序 wrapper，防 ORDER BY 泄入 count）。 */
    private static QueryWrapper unreadQuery(Long recipientId) {
        return QueryWrapper.create()
                .where(SysMessage::getRecipientId).eq(recipientId)
                .and(SysMessage::getIsRead).eq(false);
    }
}
