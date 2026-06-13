package org.amit.finwise.cfo.service;

import org.amit.finwise.cfo.service.ContextAssemblyService.Section;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for DF-7 budget-aware, deterministic context assembly. */
class ContextAssemblyServiceTest {

    // Small budgets so the math is easy to reason about (CHARS_PER_TOKEN = 4).
    private final ContextAssemblyService svc =
            new ContextAssemblyService(100, 1000, 1000, 1000, 200);

    private String line(String tag, int chars) {
        return tag + "x".repeat(Math.max(0, chars - tag.length()));
    }

    @Test
    void keepsEverythingWhenUnderBudget() {
        String out = svc.assembleWithBudget(List.of(
                ContextAssemblyService.section("risk", "RISK block", 1000),
                ContextAssemblyService.section("news", "NEWS block", 1000)), 1000);
        assertTrue(out.contains("RISK block"));
        assertTrue(out.contains("NEWS block"));
    }

    @Test
    void dropsLowestPriorityWhenOverBudget() {
        // Each section ~100 tokens (400 chars); budget only fits the first.
        Section risk = ContextAssemblyService.section("risk", line("RISK\n", 380), 1000);
        Section goals = ContextAssemblyService.section("goals", line("GOALS\n", 380), 1000);
        String out = svc.assembleWithBudget(List.of(risk, goals), 110);
        assertTrue(out.contains("RISK"), "highest priority must survive");
        assertFalse(out.contains("GOALS"), "lowest priority must be dropped");
    }

    @Test
    void truncatesOnLineBoundaryWithMarker() {
        StringBuilder body = new StringBuilder();
        for (int i = 1; i <= 60; i++) body.append("line").append(i).append('\n');  // ~400 chars
        // cap 40 tokens ≈ 160 chars → early lines survive, late lines clipped + marked.
        String out = svc.assembleWithBudget(
                List.of(ContextAssemblyService.section("s", body.toString(), 40)), 1000);
        assertTrue(out.contains("line1\n"), "early content must survive");
        assertTrue(out.contains("truncated"), "must mark the clip");
        assertFalse(out.contains("line60"), "tail must be dropped");
        // Cut on a newline boundary: the marker is introduced by its own newline,
        // so kept content runs "…lineK\n…[section truncated…" — never a half line.
        assertTrue(out.contains("\n…[section truncated"));
    }

    @Test
    void perSectionCapAppliedBeforeGlobalBudget() {
        Section big = ContextAssemblyService.section("risk", line("RISK\n", 4000), 60); // capped to ~60 tokens
        String out = svc.assembleWithBudget(List.of(big), 1000);
        assertTrue(ContextAssemblyService.estimateTokens(out) <= 62,
                "section cap should bound output regardless of large global budget");
    }

    @Test
    void budgetForKnownProviders() {
        assertEquals(100, svc.budgetFor("ollama"));
        assertEquals(1000, svc.budgetFor("claude"));
        assertEquals(200, svc.budgetFor("unknown-provider"));
        assertEquals(200, svc.budgetFor(null));
    }
}
