package com.fintech.wallet.service;

import com.fintech.wallet.dto.AuthResponse;
import com.fintech.wallet.dto.LoginRequest;
import com.fintech.wallet.dto.RegisterRequest;
import com.fintech.wallet.dto.UserSummary;
import com.fintech.wallet.dto.WalletSummary;
import com.fintech.wallet.entity.User;
import com.fintech.wallet.entity.Wallet;
import com.fintech.wallet.entity.WalletStatus;
import com.fintech.wallet.exception.DuplicateResourceException;
import com.fintech.wallet.exception.ResourceNotFoundException;
import com.fintech.wallet.repository.UserRepository;
import com.fintech.wallet.repository.WalletRepository;
import com.fintech.wallet.security.JwtService;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(
            UserRepository userRepository,
            WalletRepository walletRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService) {
        this.userRepository = userRepository;
        this.walletRepository = walletRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String normalizedEmail = request.email().trim().toLowerCase();
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new DuplicateResourceException("An account with email " + normalizedEmail + " already exists");
        }

        User user = User.builder()
                .email(normalizedEmail)
                .passwordHash(passwordEncoder.encode(request.password()))
                .fullName(request.fullName().trim())
                .build();
        User savedUser = userRepository.save(user);

        Wallet wallet = Wallet.builder()
                .user(savedUser)
                .currency("INR")
                .balance(0L)
                .status(WalletStatus.ACTIVE)
                .version(0L)
                .build();
        Wallet savedWallet = walletRepository.save(wallet);

        String token = jwtService.generateToken(savedUser.getId(), savedUser.getEmail());

        return AuthResponse.of(
                token,
                new UserSummary(savedUser.getId(), savedUser.getEmail(), savedUser.getFullName(), savedUser.getCreatedAt()),
                new WalletSummary(savedWallet.getId(), savedWallet.getCurrency(), savedWallet.getBalance(), savedWallet.getStatus())
        );
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        String normalizedEmail = request.email().trim().toLowerCase();
        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid email or password");
        }

        Wallet wallet = walletRepository.findByUser_Id(user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("No wallet associated with user account"));

        String token = jwtService.generateToken(user.getId(), user.getEmail());

        return AuthResponse.of(
                token,
                new UserSummary(user.getId(), user.getEmail(), user.getFullName(), user.getCreatedAt()),
                new WalletSummary(wallet.getId(), wallet.getCurrency(), wallet.getBalance(), wallet.getStatus())
        );
    }
}
