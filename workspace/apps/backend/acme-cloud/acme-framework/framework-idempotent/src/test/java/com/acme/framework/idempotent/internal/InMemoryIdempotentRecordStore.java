package com.acme.framework.idempotent.internal;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版幂等记录存储（单测替身；三态语义与 Redis 实现一致）。
 */
class InMemoryIdempotentRecordStore implements IdempotentRecordStore {

    private record Record(String state, String result) {
    }

    private final Map<String, Record> records = new ConcurrentHashMap<>();

    @Override
    public Claim tryBegin(String key, Duration processingTtl) {
        Record existing = records.get(key);
        if (existing != null) {
            return "D".equals(existing.state()) ? Claim.DUPLICATE : Claim.IN_FLIGHT;
        }
        Record created = new Record("P", null);
        Record raced = records.putIfAbsent(key, created);
        return raced == null ? Claim.CLAIMED : ("D".equals(raced.state()) ? Claim.DUPLICATE : Claim.IN_FLIGHT);
    }

    @Override
    public void markDone(String key, String resultJson, Duration resultTtl) {
        records.put(key, new Record("D", resultJson));
    }

    @Override
    public void markFailed(String key) {
        records.computeIfPresent(key, (k, v) -> "P".equals(v.state()) ? null : v);
    }

    @Override
    public String findResult(String key) {
        Record record = records.get(key);
        return record == null ? null : record.result();
    }
}
