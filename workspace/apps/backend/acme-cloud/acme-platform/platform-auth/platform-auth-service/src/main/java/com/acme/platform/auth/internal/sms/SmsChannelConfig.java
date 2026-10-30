package com.acme.platform.auth.internal.sms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 开发态短信通道装配：默认日志通道（验证码经 WARN 结构化日志输出，本地联调可读）；
 * 生产通道以 Bean 覆盖（@ConditionalOnMissingBean 缺省装配）。
 * 14.3.3：验证码属一次性凭证，日志通道仅限开发/联调环境，生产部署必须覆盖或关闭 C 端登录。
 */
@Configuration
public class SmsChannelConfig {

    @Bean
    @ConditionalOnMissingBean(SmsChannel.class)
    public SmsChannel loggingSmsChannel() {
        return new LoggingSmsChannel();
    }

    static final class LoggingSmsChannel implements SmsChannel {

        private static final Logger log = LoggerFactory.getLogger(LoggingSmsChannel.class);

        @Override
        public void sendCaptcha(String phone, String code) {
            log.warn("[DEV-SMS] C 端验证码：phone={} code={}（开发通道，生产环境必须接入真实通道）", phone, code);
        }
    }
}
