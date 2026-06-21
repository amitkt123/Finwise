package org.amit.finwise.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.UserProfile;
import org.amit.finwise.cfo.repository.UserProfileRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Set;

@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
public class DevUserSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserProfileRepository userProfileRepository;

    @Value("${cfo.user.id:amit}")
    private String seedUserId;

    @Value("${cfo.user.email:}")
    private String seedUserEmail;

    @Value("${cfo.user.name:Amit}")
    private String seedUserName;

    @Override
    public void run(String... args) {
        if (userRepository.existsByUsername(seedUserId)) {
            return;
        }

        String email = (seedUserEmail == null || seedUserEmail.isBlank())
                ? seedUserId + "@local.dev"
                : seedUserEmail;

        User user = User.builder()
                .username(seedUserId)
                .email(email)
                .passwordHash(passwordEncoder.encode("changeme"))
                .roles(Set.of(Role.ROLE_USER, Role.ROLE_ADMIN))
                .enabled(true)
                .build();
        userRepository.save(user);

        if (userProfileRepository.findByUserId(seedUserId).isEmpty()) {
            userProfileRepository.save(UserProfile.builder()
                    .userId(seedUserId)
                    .name(seedUserName)
                    .email(email)
                    .build());
        }

        log.info("[DevUserSeeder] Seeded dev user '{}' with ROLE_USER + ROLE_ADMIN. Default password: changeme", seedUserId);
    }
}
