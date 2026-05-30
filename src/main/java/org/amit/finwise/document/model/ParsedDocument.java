package org.amit.finwise.document.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class ParsedDocument {
    Long documentId;
    String originalFilename;
    DocumentUpload.DocumentType documentType;
    String institution;
    DocumentUpload.ParseStatus parseStatus;
    String extractedText;
    List<ParsedTransaction> transactions;
    String errorMessage;

    public int getTransactionCount() {
        return transactions == null ? 0 : transactions.size();
    }
}
