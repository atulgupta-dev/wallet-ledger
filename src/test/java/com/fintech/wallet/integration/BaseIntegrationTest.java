package com.fintech.wallet.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.wallet.controller.WalletController;
import com.fintech.wallet.entity.User;
import com.fintech.wallet.entity.Wallet;
import com.fintech.wallet.entity.WalletStatus;
import com.fintech.wallet.repository.LedgerEntryRepository;
import com.fintech.wallet.repository.TransactionRepository;
import com.fintech.wallet.repository.UserRepository;
import com.fintech.wallet.repository.WalletRepository;
import com.fintech.wallet.service.IdempotencyService;
import com.fintech.wallet.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@ActiveProfiles("test")
public abstract class BaseIntegrationTest {

    protected static final PostgreSQLContainer<?> postgres;
    protected static final GenericContainer<?> redis;
    protected static final KafkaContainer kafka;

    static {
        System.setProperty("api.version", "1.44");
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            if (System.getenv("DOCKER_HOST") == null) {
                System.setProperty("docker.host", "npipe:////./pipe/dockerDesktopLinuxEngine");
            }
        }

        postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("wallet_test")
                .withUsername("postgres")
                .withPassword("postgres");
        postgres.start();

        redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379);
        redis.start();

        kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));
        kafka.start();
    }

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 50);

        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));

        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);

        registry.add("security.rate-limit.enabled", () -> "false");
        registry.add("app.rate-limit.enabled", () -> "false");
    }

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected WalletRepository walletRepository;

    @Autowired
    protected TransactionRepository transactionRepository;

    @Autowired
    protected LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    protected WalletService walletService;

    @Autowired
    protected IdempotencyService idempotencyService;

    @Autowired
    protected WalletController walletController;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected StringRedisTemplate redisTemplate;

    @Autowired
    protected ObjectMapper objectMapper;

    @BeforeEach
    void cleanDatabaseAndRedis() {
        ledgerEntryRepository.deleteAll();
        transactionRepository.deleteAll();
        walletRepository.deleteAll();
        userRepository.deleteAll();

        if (redisTemplate.getConnectionFactory() != null) {
            redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        }
    }

    protected User createTestUserWithWallet(String email, String fullName, Long initialBalance) {
        User user = User.builder()
                .email(email)
                .passwordHash("$2a$10$7EqJtq98hPqEX7fNZaFWoO8dFl82TjZ.9hSghN.R6/j4NqmD6wYfe")
                .fullName(fullName)
                .build();
        user = userRepository.save(user);

        Wallet wallet = Wallet.builder()
                .user(user)
                .currency("INR")
                .balance(initialBalance)
                .status(WalletStatus.ACTIVE)
                .version(0L)
                .build();
        walletRepository.save(wallet);

        return user;
    }
}
