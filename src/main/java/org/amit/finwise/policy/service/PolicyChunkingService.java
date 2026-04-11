package org.amit.finwise.policy.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class PolicyChunkingService {

    @Value("${policy.chunk.max-chars:1800}")
    private int maxChunkChars;

    @Value("${policy.chunk.overlap-chars:200}")
    private int overlapChars;

    public List<ChunkDraft> chunk(String normalizedText) {
        if (normalizedText == null || normalizedText.isBlank()) {
            return List.of();
        }

        String[] paragraphs = normalizedText.split("\\n\\s*\\n");
        List<ChunkDraft> chunks = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        String currentHeading = null;
        int chunkIndex = 0;

        for (String paragraph : paragraphs) {
            String trimmed = paragraph.trim();
            if (trimmed.isBlank()) {
                continue;
            }

            if (isHeading(trimmed)) {
                if (!buffer.isEmpty()) {
                    chunks.add(buildChunk(chunkIndex++, currentHeading, buffer.toString()));
                    buffer = new StringBuilder(overlap(buffer.toString()));
                }
                currentHeading = trimmed;
                buffer.append(trimmed).append("\n");
                continue;
            }

            if (buffer.length() + trimmed.length() + 2 > maxChunkChars && !buffer.isEmpty()) {
                chunks.add(buildChunk(chunkIndex++, currentHeading, buffer.toString()));
                buffer = new StringBuilder(overlap(buffer.toString()));
            }

            buffer.append(trimmed).append("\n\n");
        }

        if (!buffer.isEmpty()) {
            chunks.add(buildChunk(chunkIndex, currentHeading, buffer.toString()));
        }

        return chunks;
    }

    private ChunkDraft buildChunk(int chunkIndex, String heading, String content) {
        String trimmed = content.trim();
        return new ChunkDraft(
                chunkIndex,
                heading,
                heading,
                heading != null ? heading : "Chunk " + (chunkIndex + 1),
                trimmed,
                buildKeywordText(heading, trimmed)
        );
    }

    private String buildKeywordText(String heading, String content) {
        String base = heading == null ? content : heading + "\n" + content;
        return base.length() > 2000 ? base.substring(0, 2000) : base;
    }

    private String overlap(String content) {
        if (content.length() <= overlapChars) {
            return content;
        }
        return content.substring(content.length() - overlapChars);
    }

    private boolean isHeading(String text) {
        return text.length() <= 120
                && (text.equals(text.toUpperCase())
                || text.matches("^(section|chapter|part|annexure|annex|clause)\\b.*")
                || text.matches("^\\d+(\\.\\d+)*\\s+.*"));
    }

    public record ChunkDraft(
            int chunkIndex,
            String heading,
            String sectionPath,
            String citationLabel,
            String content,
            String keywordText
    ) {
    }
}
