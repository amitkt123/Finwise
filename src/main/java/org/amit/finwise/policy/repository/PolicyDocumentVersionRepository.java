package org.amit.finwise.policy.repository;

import org.amit.finwise.policy.model.PolicyDocument;
import org.amit.finwise.policy.model.PolicyDocumentVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PolicyDocumentVersionRepository extends JpaRepository<PolicyDocumentVersion, Long> {

    Optional<PolicyDocumentVersion> findByDocumentAndCurrentTrue(PolicyDocument document);

    List<PolicyDocumentVersion> findByDocumentOrderByVersionNumberDesc(PolicyDocument document);
}
