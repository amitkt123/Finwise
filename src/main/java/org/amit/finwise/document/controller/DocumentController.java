package org.amit.finwise.document.controller;

import lombok.RequiredArgsConstructor;
import org.amit.finwise.document.model.DocumentUpload;
import org.amit.finwise.document.model.ParsedDocument;
import org.amit.finwise.document.service.DocumentParserService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentParserService documentParserService;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentUpload> upload(
            @AuthenticationPrincipal UserDetails principal,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "password", required = false) String password) {
        return ResponseEntity.ok(
                documentParserService.uploadAndParse(principal.getUsername(), file, password));
    }

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

    @GetMapping
    public ResponseEntity<List<DocumentUpload>> list(
            @AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.ok(documentParserService.getDocuments(principal.getUsername()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<DocumentUpload> get(@PathVariable Long id) {
        return documentParserService.getDocument(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/parse-result")
    public ResponseEntity<ParsedDocument> getParseResult(@PathVariable Long id) {
        return documentParserService.getParsedDocument(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
