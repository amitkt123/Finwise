package org.amit.finwise.admin.service;

import lombok.RequiredArgsConstructor;
import org.amit.finwise.document.model.DocumentUpload;
import org.amit.finwise.document.model.ParsedDocument;
import org.amit.finwise.document.repository.DocumentUploadRepository;
import org.amit.finwise.document.service.DocumentParserService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.File;

@Service
@RequiredArgsConstructor
public class AdminDocumentService {

    private final DocumentUploadRepository uploadRepository;
    private final DocumentParserService documentParserService;

    public Page<DocumentUpload> listDocuments(String status, String userId, Pageable pageable) {
        Specification<DocumentUpload> spec = Specification.where(null);
        if (status != null && !status.isBlank()) {
            DocumentUpload.ParseStatus ps = DocumentUpload.ParseStatus.valueOf(status.toUpperCase());
            spec = spec.and((root, q, cb) -> cb.equal(root.get("parseStatus"), ps));
        }
        if (userId != null && !userId.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("userId"), userId));
        }
        return uploadRepository.findAll(spec, pageable);
    }

    @Transactional
    public DocumentUpload reparseDocument(Long id, String password) {
        DocumentUpload upload = uploadRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found: " + id));

        File file = new File(upload.getStoragePath());
        if (!file.exists()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Storage file missing: " + upload.getStoragePath());
        }

        upload.setParseStatus(DocumentUpload.ParseStatus.PROCESSING);
        upload.setErrorMessage(null);
        uploadRepository.save(upload);

        try {
            ParsedDocument parsed = documentParserService.parseFile(file, password);
            upload.setDocumentType(parsed.getDocumentType());
            upload.setInstitution(parsed.getInstitution());
            upload.setExtractedText(parsed.getExtractedText());
            upload.setParsedRecordsExtracted(parsed.getTransactionCount());
            upload.setParseStatus(parsed.getParseStatus());
            upload.setErrorMessage(parsed.getErrorMessage());
        } catch (Exception e) {
            upload.setParseStatus(DocumentUpload.ParseStatus.FAILED);
            upload.setErrorMessage(e.getMessage());
        }

        return uploadRepository.save(upload);
    }
}
