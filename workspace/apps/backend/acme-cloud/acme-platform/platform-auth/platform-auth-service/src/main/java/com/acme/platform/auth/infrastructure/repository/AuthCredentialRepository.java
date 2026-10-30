package com.acme.platform.auth.infrastructure.repository;

import com.acme.platform.auth.domain.AuthCredential;
import org.springframework.data.jpa.repository.JpaRepository;

/** 凭据仓库（按 user_id 唯一定位，无租户过滤 Q-D04）。 */
public interface AuthCredentialRepository extends JpaRepository<AuthCredential, Long> {

    AuthCredential findByUserId(Long userId);
}
