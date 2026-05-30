package org.amit.finwise.policy.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class PolicySearchIndexService {

    private final JdbcTemplate jdbc;

    public PolicySearchIndexService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void initIndexes() {
        createIndex("idx_policy_documents_fts", """
                CREATE INDEX IF NOT EXISTS idx_policy_documents_fts
                ON policy_documents
                USING gin ((
                        setweight(to_tsvector('simple', coalesce(title, '')), 'A')
                     || setweight(to_tsvector('simple', coalesce(summary, '')), 'B')
                     || setweight(to_tsvector('simple', coalesce(tags_csv, '')), 'A')
                     || setweight(to_tsvector('simple', coalesce(affected_sectors_csv, '')), 'A')
                ))
                """);

        createIndex("idx_policy_chunks_fts", """
                CREATE INDEX IF NOT EXISTS idx_policy_chunks_fts
                ON policy_chunks
                USING gin ((
                        setweight(to_tsvector('simple', coalesce(heading, '')), 'A')
                     || setweight(to_tsvector('simple', coalesce(section_path, '')), 'B')
                     || setweight(to_tsvector('simple', coalesce(keyword_text, '')), 'A')
                     || setweight(to_tsvector('simple', coalesce(content, '')), 'C')
                ))
                """);

        createIndex("idx_policy_impacts_fts", """
                CREATE INDEX IF NOT EXISTS idx_policy_impacts_fts
                ON policy_impacts
                USING gin ((
                        setweight(to_tsvector('simple', coalesce(subject_key, '')), 'A')
                     || setweight(to_tsvector('simple', coalesce(subject_label, '')), 'A')
                     || setweight(to_tsvector('simple', coalesce(tags_csv, '')), 'A')
                     || setweight(to_tsvector('simple', coalesce(impact_summary, '')), 'B')
                     || setweight(to_tsvector('simple', coalesce(reasoning_note, '')), 'C')
                     || setweight(to_tsvector('simple', coalesce(affected_party, '')), 'B')
                     || setweight(to_tsvector('simple', coalesce(implementation_summary, '')), 'C')
                     || setweight(to_tsvector('simple', coalesce(falsification_signal, '')), 'D')
                ))
                """);
    }

    private void createIndex(String indexName, String sql) {
        try {
            jdbc.execute(sql);
        } catch (Exception e) {
            log.warn("Policy full-text search index {} was not created: {}", indexName, e.getMessage());
        }
    }
}
