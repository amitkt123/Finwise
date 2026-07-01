package org.amit.finwise.cfo.service.fiduciary;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Conflict disclosure statement is dynamic — it evolves as the business model
 * adds transaction commissions. Update this config when new revenue streams are added.
 * SEBI IA Regulations 2020 require disclosure per recommendation.
 */
@Component
@ConfigurationProperties(prefix = "cfo.fiduciary")
public class ConflictDisclosureConfig {

    private String conflictStatement =
        "Conflict: NONE. Finwise earns a flat subscription fee only. " +
        "No commission is earned on any security or product recommended here.";

    private String engineVersion = "insight-engine-v2";

    public String getConflictStatement() { return conflictStatement; }
    public void setConflictStatement(String s) { this.conflictStatement = s; }

    public String getEngineVersion() { return engineVersion; }
    public void setEngineVersion(String v) { this.engineVersion = v; }
}
