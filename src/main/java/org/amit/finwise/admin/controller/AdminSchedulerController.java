package org.amit.finwise.admin.controller;

import lombok.RequiredArgsConstructor;
import org.amit.finwise.admin.service.AdminSchedulerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/scheduler")
@RequiredArgsConstructor
public class AdminSchedulerController {

    private final AdminSchedulerService adminSchedulerService;

    @GetMapping("/jobs")
    public List<AdminSchedulerService.SchedulerJobStatus> listJobs() {
        return adminSchedulerService.listJobs();
    }

    @PostMapping("/jobs/{jobName}/trigger")
    public ResponseEntity<Map<String, String>> triggerJob(@PathVariable String jobName) {
        adminSchedulerService.triggerJob(jobName);
        return ResponseEntity.accepted()
                .body(Map.of("status", "triggered", "job", jobName));
    }
}
