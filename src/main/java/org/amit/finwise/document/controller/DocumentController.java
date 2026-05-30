package org.amit.finwise.document.controller;

import lombok.RequiredArgsConstructor;
import org.amit.finwise.document.model.DocumentUpload;
import org.amit.finwise.document.model.ParsedDocument;
import org.amit.finwise.document.service.DocumentParserService;
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
     * The document module only stores the file metadata and parse result.
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentUpload> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "password", required = false) String password) {
        return ResponseEntity.ok(documentParserService.uploadAndParse(file, password));
    }

    /**
     * POST /api/documents/parse
     * Extract text and parse neutral document records without saving expenses or budgets.
     */
    @PostMapping(value = "/parse", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ParsedDocument> parse(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "password", required = false) String password) {
        try {
            return ResponseEntity.ok(
                    documentParserService.parseBytes(file.getBytes(), password, file.getOriginalFilename()));
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to read uploaded document", e);
        }
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

    /**
     * GET /api/documents/{id}/parse-result
     */
    @GetMapping("/{id}/parse-result")
    public ResponseEntity<ParsedDocument> getParseResult(@PathVariable Long id) {
        return documentParserService.getParsedDocument(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

}
