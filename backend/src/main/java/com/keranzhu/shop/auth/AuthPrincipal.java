package com.keranzhu.shop.auth;

/**
 * The authenticated caller, extracted from a verified JWT.
 * Controllers receive it with {@code @AuthenticationPrincipal}.
 */
public record AuthPrincipal(Long userId, String email, Role role) {
}
