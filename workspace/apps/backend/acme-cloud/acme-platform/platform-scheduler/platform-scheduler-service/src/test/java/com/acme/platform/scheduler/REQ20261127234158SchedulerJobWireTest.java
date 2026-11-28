package com.acme.platform.scheduler;

import com.acme.common.serialization.JsonModules;
import com.acme.platform.scheduler.application.JobSaveRequest;
import com.acme.platform.scheduler.application.JobView;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-20261127-234158（P1-10）：JobSaveRequest/JobView wire 契约测试（真实 JsonModules.standard()
 * mapper 断言 writeValueAsString/readValue 产物，SystemApiContractTest 先例口径，非 MSW）。
 * 锁定：请求/视图字段名 cronExpr/fixedRateMs（FE 历史短名 cron/fixedRate 不得映射）、
 * 视图侧一切 Long→JSON 字符串（fixedRateMs/timeoutMs/version，shardTotal 为 Integer 保持数值）、
 * 请求侧反序列化接受 number/string 双口径（LongToStringModule）。
 */
class REQ20261127234158SchedulerJobWireTest {

    private final JsonMapper mapper = buildMapper();

    private static JsonMapper buildMapper() {
        JsonMapper.Builder builder = JsonMapper.builder();
        for (JacksonModule module : JsonModules.standard()) {
            builder.addModule(module);
        }
        return builder.build();
    }

    @Test
    void jobViewSerializesLongFieldsAsJsonStringsAndKeepsShardTotalNumeric() {
        // JobView 视图字段 triggerType/misfirePolicy/status 本身即 String（枚举名透传）
        JobView view = new JobView("42", "demo-nightly", "acme-demo", "demoHandler",
                "CRON", "0 0 3 * * *", 5000L, null, null, null,
                2, true, 60000L, "FIRE_ONCE_NOW", "DISABLED",
                Instant.parse("2026-10-27T03:00:00Z"), "nightly demo", 7L);
        String json = mapper.writeValueAsString(view);
        // 字段名锁定（AC-6 golden）：jobKey/cronExpr/fixedRateMs 长名
        assertThat(json).contains("\"jobKey\":\"demo-nightly\"");
        assertThat(json).contains("\"cronExpr\":\"0 0 3 * * *\"");
        // 视图侧 Long→JSON 字符串（LongToStringModule 全局；AC-5 FE 声明 string 的事实源）
        assertThat(json).contains("\"id\":\"42\"");
        assertThat(json).contains("\"fixedRateMs\":\"5000\"");
        assertThat(json).contains("\"timeoutMs\":\"60000\"");
        assertThat(json).contains("\"version\":\"7\"");
        // Integer 保持数值口径（AC-5：shardTotal 保持 number）
        assertThat(json).contains("\"shardTotal\":2");
        // 枚举名透传 + Instant ISO-8601
        assertThat(json).contains("\"triggerType\":\"CRON\"");
        assertThat(json).contains("\"status\":\"DISABLED\"");
        assertThat(json).contains("\"nextFireTime\":\"2026-10-27T03:00:00Z\"");
        // 回读稳定
        JobView back = mapper.readValue(json, JobView.class);
        assertThat(back.id()).isEqualTo("42");
        assertThat(back.jobKey()).isEqualTo("demo-nightly");
        assertThat(back.cronExpr()).isEqualTo("0 0 3 * * *");
        assertThat(back.fixedRateMs()).isEqualTo(5000L);
        assertThat(back.timeoutMs()).isEqualTo(60000L);
        assertThat(back.shardTotal()).isEqualTo(2);
        assertThat(back.version()).isEqualTo(7L);
    }

    @Test
    void jobSaveRequestDeserializesLongNameFieldsAcceptingNumberAndString() {
        // 正例：长名 JSON（fixedRateMs 数值口径 + timeoutMs 字符串口径混排）→ JobSaveRequest
        JobSaveRequest fromNumber = mapper.readValue(
                "{\"jobKey\":\"demo-nightly\",\"appName\":\"acme-demo\",\"handler\":\"demoHandler\","
                        + "\"triggerType\":\"CRON\",\"cronExpr\":\"0 0 3 * * *\",\"fixedRateMs\":5000,"
                        + "\"timeoutMs\":\"60000\"}",
                JobSaveRequest.class);
        assertThat(fromNumber.jobKey()).isEqualTo("demo-nightly");
        assertThat(fromNumber.cronExpr()).isEqualTo("0 0 3 * * *");
        assertThat(fromNumber.fixedRateMs()).isEqualTo(5000L);
        assertThat(fromNumber.timeoutMs()).isEqualTo(60000L);
        assertThat(fromNumber.triggerType().name()).isEqualTo("CRON");
        // 未提供的组件保持 null（缺省语义）
        assertThat(fromNumber.onceFireAt()).isNull();
        // 双口径（LongToStringSerializationTest ③）：fixedRateMs 字符串 "5000" 同样反序列化为 5000L
        JobSaveRequest fromString = mapper.readValue(
                "{\"cronExpr\":\"0 0 3 * * *\",\"fixedRateMs\":\"5000\"}", JobSaveRequest.class);
        assertThat(fromString.cronExpr()).isEqualTo("0 0 3 * * *");
        assertThat(fromString.fixedRateMs()).isEqualTo(5000L);
    }

    @Test
    void jobSaveRequestShortNameJsonIsIgnoredLeavingLongNameFieldsNull() {
        // 短名负例（AC-6，实测口径）：FE 历史短名 cron/fixedRate 不映射进长名字段——
        // JsonModules.standard() 对未识别键不 fail-fast 而是忽略（实测：2026-11-28 本机 mvn 运行，
        // fail-fast 断言红、忽略断言绿），故 readValue 正常返回且长名字段保持 null
        String shortNameJson = "{\"cron\":\"0 0 3 * * *\",\"fixedRate\":5000}";
        JobSaveRequest back = mapper.readValue(shortNameJson, JobSaveRequest.class);
        assertThat(back.cronExpr()).isNull();
        assertThat(back.fixedRateMs()).isNull();
        assertThat(back.jobKey()).isNull();
        assertThat(back.triggerType()).isNull();
    }
}
