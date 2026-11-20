package com.fintech.wallet.dto;

public record AuthResponse(
    String token,
    String tokenType,
    UserSummary user,
    WalletSummary wallet
) {
    public static AuthResponse of(String token, UserSummary user, WalletSummary wallet) {
        return new AuthResponse(token, "Bearer", user, wallet);
    }
}
