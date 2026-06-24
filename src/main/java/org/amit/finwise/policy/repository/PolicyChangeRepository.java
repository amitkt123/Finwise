package org.amit.finwise.policy.repository;

import org.amit.finwise.policy.model.PolicyChange;
import org.amit.finwise.policy.model.PolicyDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PolicyChangeRepository extends JpaRepository<PolicyChange, Long> {

    List<PolicyChange> findBySubjectKeyOrderByCreatedAtDesc(String subjectKey);

    List<PolicyChange> findByDocumentOrderByCreatedAtDesc(PolicyDocument document);

    @Query("SELECT DISTINCT c.subjectKey FROM PolicyChange c WHERE c.subjectKey IS NOT NULL ORDER BY c.subjectKey")
    List<String> findDistinctSubjectKeys();

    boolean existsByToVersionId(Long toVersionId);
}
