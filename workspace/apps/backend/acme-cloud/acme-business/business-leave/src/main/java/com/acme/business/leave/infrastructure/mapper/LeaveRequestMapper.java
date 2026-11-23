package com.acme.business.leave.infrastructure.mapper;

import com.acme.business.leave.domain.LeaveRequest;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * leave_request mapper（@Mapper：单体经 BusinessLeaveConfiguration @MapperScan 收编——
 * bootstrap 主包 AutoConfigurationPackages 不含 business mapper 面；standalone 服务化
 * （不在本期）经自身 Application 主包自动注册）。
 */
@Mapper
public interface LeaveRequestMapper extends BaseMapper<LeaveRequest> {
}
