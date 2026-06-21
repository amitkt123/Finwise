package org.amit.finwise.admin.controller;

import lombok.RequiredArgsConstructor;
import org.amit.finwise.admin.service.AdminOverviewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminOverviewController {

    private final AdminOverviewService adminOverviewService;

    @GetMapping("/overview")
    public AdminOverviewService.AdminOverview overview() {
        return adminOverviewService.overview();
    }
}
