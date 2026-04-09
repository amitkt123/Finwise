package org.amit.expensetracker.document.controller;

import lombok.RequiredArgsConstructor;
import org.amit.expensetracker.document.model.DocumentUpload;
import org.amit.expensetracker.document.service.DocumentParserService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentParserService documentParserService;

    /**
     * POST /api/documents/upload
     * Upload a PDF statement (bank, MF, demat, credit card).
     * Parsed debit entries are automatically saved as Expense records —
     * budget limits are updated and the CFO advisor can reference them.
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentUpload> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "password", required = false) String password) {
        return ResponseEntity.ok(documentParserService.uploadAndParse(file, password));
    }

    /**
     * GET /api/documents
     * List all uploaded documents for the configured user.
     */
    @GetMapping
    public ResponseEntity<List<DocumentUpload>> list() {
        return ResponseEntity.ok(documentParserService.getDocuments());
    }

    /**
     * GET /api/documents/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<DocumentUpload> get(@PathVariable Long id) {
        return documentParserService.getDocument(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
