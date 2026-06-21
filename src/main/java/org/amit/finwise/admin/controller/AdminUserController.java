package org.amit.finwise.admin.controller;

import lombok.RequiredArgsConstructor;
import org.amit.finwise.admin.service.AdminUserService;
import org.amit.finwise.auth.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService adminUserService;

    @GetMapping
    public Page<AdminUserService.UserDto> listUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String search) {
        return adminUserService.listUsers(PageRequest.of(page, size, Sort.by("username")), search);
    }

    @GetMapping("/{username}")
    public AdminUserService.UserDto getUser(@PathVariable String username) {
        return adminUserService.getUser(username);
    }

    @PutMapping("/{username}/enabled")
    public AdminUserService.UserDto setEnabled(
            @PathVariable String username,
            @RequestBody EnabledRequest body) {
        return adminUserService.setEnabled(username, body.enabled());
    }

    @PutMapping("/{username}/roles")
    public AdminUserService.UserDto setRoles(
            @PathVariable String username,
            @RequestBody RolesRequest body) {
        return adminUserService.setRoles(username, body.roles());
    }

    record EnabledRequest(boolean enabled) {}
    record RolesRequest(Set<Role> roles) {}
}
