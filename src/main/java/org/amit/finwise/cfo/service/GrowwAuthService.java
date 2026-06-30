package org.amit.finwise.cfo.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.AuthToken;
import org.amit.finwise.cfo.repository.AuthTokenRepository;
import org.amit.finwise.common.TokenEncryptionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class GrowwAuthService {

    private static final String SERVICE_NAME = "GROWW";

    private final AuthTokenRepository authTokenRepository;
    private final TokenEncryptionService tokenEncryptionService;

    /**
     * Store a Groww Bearer token manually (from browser/app session).
     */
    @Transactional
    public void saveToken(String userId, String plainToken) {
        AuthToken existing = authTokenRepository
                .findByUserIdAndServiceAndIsActiveTrue(userId, SERVICE_NAME)
                .orElse(null);

        if (existing != null) {
            existing.setEncryptedToken(encrypt(plainToken));
            existing.setLastUsedAt(null);
            authTokenRepository.save(existing);
            log.info("Updated Groww token for user {}", userId);
        } else {
            AuthToken token = AuthToken.builder()
                    .userId(userId)
                    .service(SERVICE_NAME)
                    .encryptedToken(encrypt(plainToken))
                    .isActive(true)
                    .build();
            authTokenRepository.save(token);
            log.info("Saved new Groww token for user {}", userId);
        }
    }

    /**
     * Store encrypted credentials for auto-refresh.
     */
    @Transactional
    public void saveCredentials(String userId, String credentialsJson) {
        AuthToken token = authTokenRepository
                .findByUserIdAndServiceAndIsActiveTrue(userId, SERVICE_NAME)
                .orElse(AuthToken.builder().userId(userId).service(SERVICE_NAME).isActive(true).build());

        token.setEncryptedCredentials(encrypt(credentialsJson));
        token.setAutoRefreshEnabled(true);
        authTokenRepository.save(token);
        log.info("Saved Groww credentials for auto-refresh for user {}", userId);
    }

    /**
     * Retrieve the active Bearer token for API calls.
     */
    public Optional<String> getToken(String userId) {
        return authTokenRepository
                .findByUserIdAndServiceAndIsActiveTrue(userId, SERVICE_NAME)
                .map(t -> decrypt(t.getEncryptedToken()));
    }

    /**
     * Mark token as used (for tracking).
     */
    @Transactional
    public void markTokenUsed(String userId) {
        authTokenRepository.findByUserIdAndServiceAndIsActiveTrue(userId, SERVICE_NAME)
                .ifPresent(t -> {
                    t.setLastUsedAt(LocalDateTime.now());
                    authTokenRepository.save(t);
                });
    }

    private String encrypt(String plainText) {
        return tokenEncryptionService.encrypt(plainText);
    }

    private String decrypt(String encryptedText) {
        return tokenEncryptionService.decrypt(encryptedText);
    }
}
