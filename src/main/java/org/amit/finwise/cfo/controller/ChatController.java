package org.amit.finwise.cfo.controller;

import lombok.RequiredArgsConstructor;
import org.amit.finwise.cfo.service.CFOAdvisorService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final CFOAdvisorService cfoAdvisorService;

    @PostMapping("/message")
    public ResponseEntity<ChatResponse> sendMessage(
            @AuthenticationPrincipal UserDetails principal,
            @RequestBody ChatRequest request) {
        String response = cfoAdvisorService.chat(List.of(), principal.getUsername(), request.message());
        return ResponseEntity.ok(new ChatResponse(response));
    }

    record ChatRequest(String message) {}
    record ChatResponse(String response) {}
}
