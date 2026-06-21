package org.amit.finwise.auth;

import lombok.RequiredArgsConstructor;
import org.amit.finwise.cfo.model.UserProfile;
import org.amit.finwise.cfo.repository.UserProfileRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final UserProfileRepository userProfileRepository;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@RequestBody RegisterRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            return ResponseEntity.badRequest().body(new AuthResponse(null, "Username already taken"));
        }
        if (userRepository.existsByEmail(request.email())) {
            return ResponseEntity.badRequest().body(new AuthResponse(null, "Email already registered"));
        }

        User user = User.builder()
                .username(request.username())
                .email(request.email())
                .passwordHash(passwordEncoder.encode(request.password()))
                .roles(Set.of(Role.ROLE_USER))
                .enabled(true)
                .build();
        userRepository.save(user);

        if (userProfileRepository.findByUserId(request.username()).isEmpty()) {
            userProfileRepository.save(UserProfile.builder()
                    .userId(request.username())
                    .name(request.name() != null ? request.name() : request.username())
                    .email(request.email())
                    .build());
        }

        String token = jwtService.issueToken(user.getUsername());
        return ResponseEntity.ok(new AuthResponse(token, "Registered successfully"));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody LoginRequest request) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        } catch (BadCredentialsException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new AuthResponse(null, "Invalid credentials"));
        }
        String token = jwtService.issueToken(request.username());
        return ResponseEntity.ok(new AuthResponse(token, "Login successful"));
    }

    @GetMapping("/me")
    public ResponseEntity<MeResponse> me(@AuthenticationPrincipal UserDetails userDetails) {
        User user = userRepository.findByUsername(userDetails.getUsername())
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found"));
        return ResponseEntity.ok(new MeResponse(user.getUsername(), user.getEmail(), user.getRoles()));
    }

    record RegisterRequest(String username, String email, String password, String name) {}
    record LoginRequest(String username, String password) {}
    record AuthResponse(String token, String message) {}
    record MeResponse(String username, String email, Set<Role> roles) {}
}
