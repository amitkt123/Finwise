package org.amit.finwise.admin.service;

import lombok.RequiredArgsConstructor;
import org.amit.finwise.auth.Role;
import org.amit.finwise.auth.User;
import org.amit.finwise.auth.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AdminUserService {

    private final UserRepository userRepository;

    public record UserDto(
            Long id,
            String username,
            String email,
            Set<Role> roles,
            boolean enabled,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {}

    public Page<UserDto> listUsers(Pageable pageable, String search) {
        Page<User> page = (search != null && !search.isBlank())
                ? userRepository.findByUsernameContainingIgnoreCase(search, pageable)
                : userRepository.findAll(pageable);
        return page.map(this::toDto);
    }

    public UserDto getUser(String username) {
        return toDto(findOrThrow(username));
    }

    @Transactional
    public UserDto setEnabled(String username, boolean enabled) {
        User user = findOrThrow(username);
        user.setEnabled(enabled);
        return toDto(userRepository.save(user));
    }

    @Transactional
    public UserDto setRoles(String username, Set<Role> roles) {
        User user = findOrThrow(username);
        user.getRoles().clear();
        user.getRoles().addAll(roles);
        return toDto(userRepository.save(user));
    }

    private User findOrThrow(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: " + username));
    }

    private UserDto toDto(User u) {
        return new UserDto(u.getId(), u.getUsername(), u.getEmail(),
                u.getRoles(), u.isEnabled(), u.getCreatedAt(), u.getUpdatedAt());
    }
}
