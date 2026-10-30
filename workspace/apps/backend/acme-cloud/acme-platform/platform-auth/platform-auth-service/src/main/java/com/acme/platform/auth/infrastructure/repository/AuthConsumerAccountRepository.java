package com.acme.platform.auth.infrastructure.repository;

import com.acme.platform.auth.domain.AuthConsumerAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** C 端账户仓库（手机号唯一定位；首登自动注册 upsert 由应用层编排）。 */
public interface AuthConsumerAccountRepository extends JpaRepository<AuthConsumerAccount, Long> {

    Optional<AuthConsumerAccount> findByPhone(String phone);
}
