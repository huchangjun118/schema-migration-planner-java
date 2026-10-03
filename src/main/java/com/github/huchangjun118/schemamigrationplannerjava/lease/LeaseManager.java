package com.github.huchangjun118.schemamigrationplannerjava.lease;

import com.github.huchangjun118.schemamigrationplannerjava.config.MigrationProperties;
import com.github.huchangjun118.schemamigrationplannerjava.store.MetadataStore;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 租约管理器。通过租约避免同一份变更被两个执行者同时应用。
 *
 * <p>租约带 TTL，过期后可被安全接管；接管后从最近已提交步骤继续，
 * 已完成步骤幂等跳过，不会重复应用。token 用于 fencing：执行者在
 * 每个关键操作前校验租约是否仍属于自己，失效则立即停止。
 */
@Component
public class LeaseManager {

    private final MetadataStore store;
    private final MigrationProperties props;

    public LeaseManager(MetadataStore store, MigrationProperties props) {
        this.store = store;
        this.props = props;
    }

    /** 租约获取结果 */
    public record LeaseResult(boolean acquired, String token, String owner, long expiresAt) {
        static LeaseResult acquired(String token, long expiresAt) {
            return new LeaseResult(true, token, null, expiresAt);
        }
        static LeaseResult busy(String owner, long expiresAt) {
            return new LeaseResult(false, null, owner, expiresAt);
        }
    }

    /**
     * 尝试获取租约（立即返回，不等待）。
     */
    public LeaseResult tryAcquire(String key, String owner) {
        long nowMs = Instant.now().toEpochMilli();
        long expiresMs = nowMs + props.getLease().getTtlMs();
        String token = UUID.randomUUID().toString();
        String now = Instant.ofEpochMilli(nowMs).toString();
        String expiresAt = Instant.ofEpochMilli(expiresMs).toString();

        // 1. 尝试直接插入（并发时可能主键冲突，落入步骤 2）
        try {
            int inserted = store.insertLease(key, owner, now, expiresAt, token);
            if (inserted > 0) {
                return LeaseResult.acquired(token, expiresMs);
            }
        } catch (org.springframework.dao.DuplicateKeyException ignore) {
            // 并发插入冲突，落入过期接管逻辑
        }
        // 2. 已存在：若已过期则接管
        int updated = store.updateLeaseIfExpired(key, owner, now, expiresAt, token, now);
        if (updated > 0) {
            return LeaseResult.acquired(token, expiresMs);
        }
        // 3. 仍被有效租约持有
        Optional<MetadataStore.LeaseRow> existing = store.getLease(key);
        return existing
                .map(l -> LeaseResult.busy(l.owner(), Instant.parse(l.expiresAt()).toEpochMilli()))
                .orElseGet(() -> LeaseResult.busy("unknown", 0));
    }

    /**
     * 获取租约，最多等待 maxWaitMs（期间轮询）。
     */
    public LeaseResult acquire(String key, String owner) {
        long deadline = System.currentTimeMillis() + props.getLease().getMaxWaitMs();
        while (true) {
            LeaseResult r = tryAcquire(key, owner);
            if (r.acquired()) {
                return r;
            }
            if (System.currentTimeMillis() >= deadline) {
                return r;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return r;
            }
        }
    }

    /**
     * 续期（fencing）。仅当租约仍属于 token 且未过期时成功。
     */
    public boolean renew(String key, String token) {
        long newExpiresMs = Instant.now().toEpochMilli() + props.getLease().getTtlMs();
        String newExpiresAt = Instant.ofEpochMilli(newExpiresMs).toString();
        return store.renewLease(key, token, newExpiresAt) > 0;
    }

    /**
     * 释放租约（仅持有者可释放）。
     */
    public boolean release(String key, String token) {
        return store.releaseLease(key, token) > 0;
    }

    /**
     * fencing 校验：租约是否仍由 token 持有且未过期。
     */
    public boolean isHeld(String key, String token) {
        Optional<MetadataStore.LeaseRow> row = store.getLease(key);
        if (row.isEmpty()) {
            return false;
        }
        MetadataStore.LeaseRow l = row.get();
        if (!l.token().equals(token)) {
            return false;
        }
        return Instant.parse(l.expiresAt()).isAfter(Instant.now());
    }

    /** 租约键：按 schema 串行化同一张表/同一个库上的变更 */
    public static String schemaLeaseKey(String schemaName) {
        return "schema:" + schemaName;
    }
}
