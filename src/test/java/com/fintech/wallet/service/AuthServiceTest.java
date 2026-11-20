package com.fintech.wallet.service;

import com.fintech.wallet.dto.AuthResponse;
import com.fintech.wallet.dto.LoginRequest;
import com.fintech.wallet.dto.RegisterRequest;
import com.fintech.wallet.entity.User;
import com.fintech.wallet.entity.Wallet;
import com.fintech.wallet.entity.WalletStatus;
import com.fintech.wallet.exception.DuplicateResourceException;
import com.fintech.wallet.repository.UserRepository;
import com.fintech.wallet.repository.WalletRepository;
import com.fintech.wallet.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    @InjectMocks
    private AuthService authService;

    private User sampleUser;
    private Wallet sampleWallet;

    @BeforeEach
    void setUp() {
        UUID userId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();

        sampleUser = User.builder()
                .id(userId)
                .email("alex@fintech.com")
                .passwordHash("hashed_password")
                .fullName("Alex Morgan")
                .createdAt(Instant.now())
                .build();

        sampleWallet = Wallet.builder()
                .id(walletId)
                .user(sampleUser)
                .currency("INR")
                .balance(0L)
                .status(WalletStatus.ACTIVE)
                .version(0L)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    @Test
    void shouldRegisterNewUserAndCreateActiveWallet() {
        RegisterRequest request = new RegisterRequest("Alex Morgan", "alex@fintech.com", "Password@123");

        when(userRepository.existsByEmail("alex@fintech.com")).thenReturn(false);
        when(passwordEncoder.encode("Password@123")).thenReturn("hashed_password");
        when(userRepository.save(any(User.class))).thenReturn(sampleUser);
        when(walletRepository.save(any(Wallet.class))).thenReturn(sampleWallet);
        when(jwtService.generateToken(sampleUser.getId(), sampleUser.getEmail())).thenReturn("mock.jwt.token");

        AuthResponse response = authService.register(request);

        assertNotNull(response);
        assertEquals("mock.jwt.token", response.token());
        assertEquals("Bearer", response.tokenType());
        assertEquals("alex@fintech.com", response.user().email());
        assertEquals(0L, response.wallet().balance());
        assertEquals(WalletStatus.ACTIVE, response.wallet().status());
        assertEquals("INR", response.wallet().currency());

        verify(walletRepository).save(any(Wallet.class));
    }

    @Test
    void shouldThrowDuplicateResourceExceptionWhenEmailExists() {
        RegisterRequest request = new RegisterRequest("Alex Morgan", "alex@fintech.com", "Password@123");
        when(userRepository.existsByEmail("alex@fintech.com")).thenReturn(true);

        assertThrows(DuplicateResourceException.class, () -> authService.register(request));
    }

    @Test
    void shouldLoginSuccessfullyWithValidCredentials() {
        LoginRequest request = new LoginRequest("alex@fintech.com", "Password@123");

        when(userRepository.findByEmail("alex@fintech.com")).thenReturn(Optional.of(sampleUser));
        when(passwordEncoder.matches("Password@123", sampleUser.getPasswordHash())).thenReturn(true);
        when(walletRepository.findByUser_Id(sampleUser.getId())).thenReturn(Optional.of(sampleWallet));
        when(jwtService.generateToken(sampleUser.getId(), sampleUser.getEmail())).thenReturn("mock.jwt.token");

        AuthResponse response = authService.login(request);

        assertNotNull(response);
        assertEquals("mock.jwt.token", response.token());
        assertEquals(sampleUser.getEmail(), response.user().email());
    }

    @Test
    void shouldThrowBadCredentialsExceptionWhenPasswordIsIncorrect() {
        LoginRequest request = new LoginRequest("alex@fintech.com", "WrongPassword");

        when(userRepository.findByEmail("alex@fintech.com")).thenReturn(Optional.of(sampleUser));
        when(passwordEncoder.matches("WrongPassword", sampleUser.getPasswordHash())).thenReturn(false);

        assertThrows(BadCredentialsException.class, () -> authService.login(request));
    }
}
