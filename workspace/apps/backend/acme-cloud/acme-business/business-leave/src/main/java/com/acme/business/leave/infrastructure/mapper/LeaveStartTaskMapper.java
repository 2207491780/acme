package com.acme.business.leave.infrastructure.mapper;

import com.acme.business.leave.domain.LeaveStartTask;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * leave_start_task mapper（@Mapper：单体经 BusinessLeaveConfiguration @MapperScan 收编——
 * 同 {@link LeaveRequestMapper} 口径）。
 */
@Mapper
public interface LeaveStartTaskMapper extends BaseMapper<LeaveStartTask> {
}
