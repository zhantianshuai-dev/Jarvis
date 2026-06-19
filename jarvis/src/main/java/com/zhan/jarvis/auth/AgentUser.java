package com.zhan.jarvis.auth;

import java.time.Instant;

/**
 * Agent HTTP API 用户表映射。
 * <p>
 * Sa-Token 只负责 token 与 loginId 的登录态关系；完整用户资料、密码哈希、
 * 锁定状态和角色仍以 PostgreSQL 的 agent_user 表为准。
 */
public record AgentUser(
        long id,
        String username,
        /** BCrypt 哈希后的密码，永远不要保存或返回明文密码。 */
        String passwordHash,
        String displayName,
        /** 禁用后即使 token 仍有效，也会在 AuthService.verifyToken 中被拒绝。 */
        boolean enabled,
        String role,
        Instant lastLoginAt,
        String lastLoginIp,
        /** 连续登录失败次数，用于达到阈值后临时锁定账号。 */
        int failedLoginCount,
        /** 非空且晚于当前时间时，登录会被拒绝。 */
        Instant lockedUntil,
        Instant createdAt,
        Instant updatedAt
) {
}
