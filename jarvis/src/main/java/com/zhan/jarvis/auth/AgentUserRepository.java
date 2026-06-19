package com.zhan.jarvis.auth;

import cn.dev33.satoken.secure.BCrypt;
import cn.hutool.core.lang.Snowflake;
import com.zhan.jarvis.config.JarvisConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
@ConditionalOnProperty(prefix = "jarvis.auth", name = "enabled", havingValue = "true")
public class AgentUserRepository {

    private static final Logger log = LoggerFactory.getLogger(AgentUserRepository.class);

    private final JdbcClient jdbc;

    private final Snowflake snowflake;

    public AgentUserRepository(JdbcClient jdbc, JarvisConfig config, Snowflake snowflake) {
        this.jdbc = jdbc;
        this.snowflake = snowflake;
        // 第一版直接在应用启动时确保表结构存在，便于本地和小规模部署。
        // 后续如果引入 Flyway/Liquibase，可以把 initSchema 迁移出去。
        if (config.auth() != null && config.auth().initSchema()) {
            initSchema();
            bootstrapAdmin(config.auth());
        }
    }

    public Optional<AgentUser> findByUsername(String username) {
        return jdbc.sql("""
                        SELECT id, username, password_hash, display_name, enabled, role, created_at, updated_at
                             , last_login_at, last_login_ip, failed_login_count, locked_until
                        FROM agent_user
                        WHERE username = :username
                        """)
                .param("username", username)
                .query(this::mapUser)
                .optional();
    }

    public Optional<AgentUser> findById(long id) {
        return jdbc.sql("""
                        SELECT id, username, password_hash, display_name, enabled, role, created_at, updated_at
                             , last_login_at, last_login_ip, failed_login_count, locked_until
                        FROM agent_user
                        WHERE id = :id
                        """)
                .param("id", id)
                .query(this::mapUser)
                .optional();
    }

    public long countUsers() {
        Long count = jdbc.sql("SELECT COUNT(*) FROM agent_user")
                .query(Long.class)
                .single();
        return count == null ? 0 : count;
    }

    public AgentUser createUser(Long userId, String username, String rawPassword, String displayName, String role) {
        // 数据库只保存 BCrypt 哈希。登录时用 BCrypt.checkpw(raw, hash) 校验。
        String passwordHash = BCrypt.hashpw(rawPassword);
        try {
            return jdbc.sql("""
                            INSERT INTO agent_user (id, username, password_hash, display_name, enabled, role)
                            VALUES (:id, :username, :password_hash, :display_name, true, :role)
                            RETURNING id, username, password_hash, display_name, enabled, role,
                                      last_login_at, last_login_ip, failed_login_count, locked_until,
                                      created_at, updated_at
                            """)
                    .param("username", username)
                    .param("password_hash", passwordHash)
                    .param("display_name", displayName)
                    .param("role", role)
                    .param("id", userId)
                    .query(this::mapUser)
                    .single();
        } catch (DuplicateKeyException e) {
            throw new AuthService.AuthException("用户名已存在");
        }
    }

    public void recordLoginSuccess(long userId, String ip) {
        jdbc.sql("""
                        UPDATE agent_user
                        SET last_login_at = NOW(),
                            last_login_ip = :ip,
                            failed_login_count = 0,
                            locked_until = NULL,
                            updated_at = NOW()
                        WHERE id = :id
                        """)
                .param("id", userId)
                .param("ip", ip == null ? "" : ip)
                .update();
    }

    public void recordLoginFailure(long userId, int maxFailedLogins, long lockSeconds) {
        if (maxFailedLogins <= 0 || lockSeconds <= 0) {
            // 配置关闭锁定策略时，只累计失败次数，不设置 locked_until。
            jdbc.sql("""
                            UPDATE agent_user
                            SET failed_login_count = failed_login_count + 1,
                                updated_at = NOW()
                            WHERE id = :id
                            """)
                    .param("id", userId)
                    .update();
            return;
        }

        jdbc.sql("""
                        UPDATE agent_user
                        SET failed_login_count = failed_login_count + 1,
                            locked_until = CASE
                                -- PostgreSQL SET 表达式里读取到的是更新前的 failed_login_count，
                                -- 因此这里手动 +1 判断本次失败后是否达到锁定阈值。
                                WHEN failed_login_count + 1 >= :max_failed
                                THEN NOW() + (:lock_seconds * INTERVAL '1 second')
                                ELSE locked_until
                            END,
                            updated_at = NOW()
                        WHERE id = :id
                        """)
                .param("id", userId)
                .param("max_failed", maxFailedLogins)
                .param("lock_seconds", lockSeconds)
                .update();
    }

    public void updatePassword(long userId, String rawPassword) {
        jdbc.sql("""
                        UPDATE agent_user
                        SET password_hash = :password_hash,
                            failed_login_count = 0,
                            locked_until = NULL,
                            updated_at = NOW()
                        WHERE id = :id
                        """)
                .param("id", userId)
                .param("password_hash", BCrypt.hashpw(rawPassword))
                .update();
    }

    private void initSchema() {
        // IF NOT EXISTS 让启动过程幂等；下面的 ADD COLUMN 用于兼容早期已创建的表。
        jdbc.sql("""
                        CREATE TABLE IF NOT EXISTS agent_user (
                            id BIGSERIAL PRIMARY KEY,
                            username VARCHAR(128) NOT NULL UNIQUE,
                            password_hash VARCHAR(255) NOT NULL,
                            display_name VARCHAR(128) NOT NULL DEFAULT '',
                            enabled BOOLEAN NOT NULL DEFAULT TRUE,
                            role VARCHAR(64) NOT NULL DEFAULT 'user',
                            last_login_at TIMESTAMPTZ,
                            last_login_ip VARCHAR(64) NOT NULL DEFAULT '',
                            failed_login_count INTEGER NOT NULL DEFAULT 0,
                            locked_until TIMESTAMPTZ,
                            created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                            updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                        )
                        """)
                .update();
        jdbc.sql("ALTER TABLE agent_user ADD COLUMN IF NOT EXISTS last_login_at TIMESTAMPTZ").update();
        jdbc.sql("ALTER TABLE agent_user ADD COLUMN IF NOT EXISTS last_login_ip VARCHAR(64) NOT NULL DEFAULT ''").update();
        jdbc.sql("ALTER TABLE agent_user ADD COLUMN IF NOT EXISTS failed_login_count INTEGER NOT NULL DEFAULT 0").update();
        jdbc.sql("ALTER TABLE agent_user ADD COLUMN IF NOT EXISTS locked_until TIMESTAMPTZ").update();
    }

    private void bootstrapAdmin(JarvisConfig.AuthConfig auth) {
        // 只在空表时创建初始管理员，避免每次启动覆盖用户自己的账号体系。
        if (countUsers() > 0) {
            return;
        }
        if (isBlank(auth.bootstrapUsername()) || isBlank(auth.bootstrapPassword())) {
            log.warn("agent_user 表为空，但未配置 JARVIS_AUTH_BOOTSTRAP_USERNAME/BOOTSTRAP_PASSWORD，无法创建初始用户");
            return;
        }
        long authId = snowflake.nextId();
        createUser(authId, auth.bootstrapUsername(), auth.bootstrapPassword(), auth.bootstrapUsername(), "admin");
        log.warn("已创建 Jarvis 初始管理员用户: username={}", auth.bootstrapUsername());
    }

    private AgentUser mapUser(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new AgentUser(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getString("password_hash"),
                rs.getString("display_name"),
                rs.getBoolean("enabled"),
                rs.getString("role"),
                toInstant(rs.getTimestamp("last_login_at")),
                rs.getString("last_login_ip"),
                rs.getInt("failed_login_count"),
                toInstant(rs.getTimestamp("locked_until")),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at"))
        );
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
