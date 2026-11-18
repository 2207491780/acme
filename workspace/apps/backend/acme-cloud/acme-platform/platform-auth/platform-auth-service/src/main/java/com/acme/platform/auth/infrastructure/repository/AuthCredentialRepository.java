package com.acme.platform.auth.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepository;
import com.acme.platform.auth.domain.AuthCredential;

/** 凭据仓库（按 user_id 唯一定位，无租户过滤 Q-D04）。 */
public interface AuthCredentialRepository extends FlexRepository<AuthCredential> {

    AuthCredential findByUserId(Long userId);
}
