package org.amit.finwise.document.repository;

import org.amit.finwise.document.model.DocumentUpload;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DocumentUploadRepository extends JpaRepository<DocumentUpload, Long> {
    List<DocumentUpload> findByUserIdOrderByCreatedAtDesc(String userId);
    List<DocumentUpload> findByUserIdAndParseStatus(String userId, DocumentUpload.ParseStatus status);
}
