package com.zhan.jarvis.auth;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.secure.BCrypt;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.lang.generator.SnowflakeGenerator;
import com.zhan.jarvis.config.JarvisConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.regex.Pattern;

@Service
@ConditionalOnProperty(prefix = "jarvis.auth", name = "enabled", havingValue = "true")
public class AuthService {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_@.-]{3,64}$");

    private final AgentUserRepository users;
    private final boolean registrationEnabled;
    private final long tokenTtlSeconds;
    private final int maxFailedLogins;
    private final long lockSeconds;
    private final Snowflake snowflake;

    public AuthService(AgentUserRepository users, JarvisConfig config, Snowflake snowflake) {
        this.users = users;
        this.snowflake = snowflake;
        var auth = config.auth();
        this.registrationEnabled = auth != null && auth.registrationEnabled();
        this.tokenTtlSeconds = auth != null && auth.tokenTtlSeconds() > 0
                ? auth.tokenTtlSeconds()
                : 86_400;
        this.maxFailedLogins = auth != null && auth.maxFailedLogins() > 0
                ? auth.maxFailedLogins()
                : 5;
        this.lockSeconds = auth != null && auth.lockSeconds() > 0
                ? auth.lockSeconds()
                : 600;
    }

    public AuthModels.LoginResponse login(String username, String password, String ip) {
        if (isBlank(username) || isBlank(password)) {
            // 不区分“用户不存在”和“密码错误”，避免泄漏账号是否存在。
            throw new AuthException("用户名或密码错误");
        }
        String normalizedUsername = username.strip();
        var user = users.findByUsername(normalizedUsername)
                .filter(AgentUser::enabled)
                .orElseThrow(() -> new AuthException("用户名或密码错误"));
        ensureNotLocked(user);
        if (!BCrypt.checkpw(password, user.passwordHash())) {
            // 密码错误时记录失败次数，达到阈值后由 Repository 设置 locked_until。
            users.recordLoginFailure(user.id(), maxFailedLogins, lockSeconds);
            throw new AuthException("用户名或密码错误");
        }

        users.recordLoginSuccess(user.id(), ip);
        // 重新读取一次，拿到 last_login_at、failed_login_count 等更新后的字段。
        user = users.findById(user.id()).orElse(user);
        String token = issueToken(user.id());
        return response(token, user);
    }

    public AuthModels.LoginResponse register(String username, String password, String displayName) {
        if (!registrationEnabled) {
            throw new AuthException("注册功能未开启");
        }
        validateRegistration(username, password);
        String normalizedUsername = username.strip();
        if (users.findByUsername(normalizedUsername).isPresent()) {
            throw new AuthException("用户名已存在");
        }

        String name = !isBlank(displayName) ? displayName.strip() : normalizedUsername;
        long userId = snowflake.nextId();
        var user = users.createUser(userId, normalizedUsername, password, name, "user");
        // 注册成功后直接签发 token，让前端可以无缝进入聊天页。
        String token = issueToken(user.id());
        return response(token, user);
    }

    public AgentUser verifyToken(String token) {
        if (isBlank(token)) {
            throw NotLoginException.newInstance(NotLoginException.NOT_TOKEN, StpUtil.TYPE,
                    "未提供 token", null);
        }
        // Sa-Token 根据 token 找回登录时绑定的 loginId。这里的 loginId 就是 agent_user.id。
        Object loginId = StpUtil.getLoginIdByToken(token);
        long userId = Long.parseLong(String.valueOf(loginId));
        // token 有效不代表用户仍可用；每次鉴权都回表检查 enabled 状态。
        return users.findById(userId)
                .filter(AgentUser::enabled)
                .orElseThrow(() -> new AuthException("用户不存在或已禁用"));
    }

    public AuthModels.UserInfo me(String token) {
        return AuthModels.UserInfo.from(verifyToken(token));
    }

    public void logout(String token) {
        if (isBlank(token)) {
            return;
        }
        // 按 token 精确登出，只让当前前端持有的 token 失效。
        StpUtil.logoutByTokenValue(token);
    }

    public void changePassword(String token, String oldPassword, String newPassword) {
        AgentUser user = verifyToken(token);
        if (isBlank(oldPassword) || !BCrypt.checkpw(oldPassword, user.passwordHash())) {
            throw new AuthException("原密码错误");
        }
        validatePassword(newPassword);
        users.updatePassword(user.id(), newPassword);
        // 改密后注销当前 token，要求用户用新密码重新登录。
        StpUtil.logoutByTokenValue(token);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static void validateRegistration(String username, String password) {
        if (isBlank(username) || !USERNAME_PATTERN.matcher(username.strip()).matches()) {
            throw new AuthException("用户名需为 3-64 位字母、数字、下划线、点、横线或 @");
        }
        validatePassword(password);
    }

    private static void validatePassword(String password) {
        if (isBlank(password) || password.length() < 8) {
            throw new AuthException("密码长度不能少于 8 位");
        }
    }

    private String issueToken(long userId) {
        // WebFlux 请求可能运行在 Reactor/异步线程中，StpUtil.login() 依赖上下文写回时
        // 容易触发 SaTokenContext 未初始化；createLoginSession 直接创建登录态并返回 token。
        return StpUtil.createLoginSession(userId, SaLoginParameter.create().setTimeout(tokenTtlSeconds));
    }

    private AuthModels.LoginResponse response(String token, AgentUser user) {
        return new AuthModels.LoginResponse(
                token,
                "Bearer",
                tokenTtlSeconds,
                user.id(),
                user.username(),
                AuthModels.UserInfo.from(user)
        );
    }

    private void ensureNotLocked(AgentUser user) {
        Instant lockedUntil = user.lockedUntil();
        if (lockedUntil != null && lockedUntil.isAfter(Instant.now())) {
            throw new AuthException("账号已锁定，请稍后再试");
        }
    }

    public static class AuthException extends RuntimeException {
        public AuthException(String message) {
            super(message);
        }
    }
}
