package org.amit.finwise.admin.controller;

import lombok.RequiredArgsConstructor;
import org.amit.finwise.admin.service.AdminDocumentService;
import org.amit.finwise.document.model.DocumentUpload;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/documents")
@RequiredArgsConstructor
public class AdminDocumentController {

    private final AdminDocumentService adminDocumentService;

    @GetMapping
    public Page<DocumentUpload> listDocuments(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return adminDocumentService.listDocuments(status, userId,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    @PostMapping("/{id}/reparse")
    public DocumentUpload reparseDocument(
            @PathVariable Long id,
            @RequestBody(required = false) ReparseRequest body) {
        String password = body != null ? body.password() : null;
        return adminDocumentService.reparseDocument(id, password);
    }

    record ReparseRequest(String password) {}
}
