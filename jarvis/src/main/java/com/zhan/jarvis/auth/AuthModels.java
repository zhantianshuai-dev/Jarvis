package com.zhan.jarvis.auth;

/**
 * AuthRouter 使用的请求/响应 DTO。
 * <p>
 * 这里集中声明 record，避免为每个很薄的 HTTP body 单独建文件。
 */
public final class AuthModels {

    private AuthModels() {
    }

    public record LoginRequest(
            String username,
            String password
    ) {
    }

    public record RegisterRequest(
            String username,
            String password,
            String displayName
    ) {
    }

    public record ChangePasswordRequest(
            String oldPassword,
            String newPassword
    ) {
    }

    public record UserInfo(
            long userId,
            String username,
            String displayName,
            String role,
            boolean enabled,
            String lastLoginAt
    ) {
        /** 返回给前端的用户视图，不包含 passwordHash 等敏感字段。 */
        public static UserInfo from(AgentUser user) {
            return new UserInfo(
                    user.id(),
                    user.username(),
                    user.displayName(),
                    user.role(),
                    user.enabled(),
                    user.lastLoginAt() == null ? null : user.lastLoginAt().toString()
            );
        }
    }

    public record LoginResponse(
            /** 前端后续放到 Authorization: Bearer 中的 token。 */
            String accessToken,
            String tokenType,
            /** token 有效期，单位秒。 */
            long expiresIn,
            long userId,
            String username,
            UserInfo user
    ) {
    }
}
