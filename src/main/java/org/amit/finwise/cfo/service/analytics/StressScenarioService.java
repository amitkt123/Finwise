package org.amit.finwise.cfo.service.analytics;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.FactorRiskReport;
import org.amit.finwise.cfo.service.macro.QuantitativeMacroState;
import org.amit.finwise.investment.model.Investment;
import org.amit.finwise.investment.repository.InvestmentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Named historical shock scenarios applied to the current book (Honest-Insights Phase E).
 *
 * <p>Each scenario is a factor-return vector (COVID 2020-03-23, Election 2024-06-04, the 2013
 * taper tantrum, plus a parametric −2σ Nifty day) loaded from {@code stress_scenarios.csv}.
 * For every scenario we produce two P&L estimates against the live portfolio:
 * <ul>
 *   <li><b>Factor-model P&amp;L</b> — {@code Σ β_f·shock_f·V} over the factors the portfolio
 *       actually loads on, using {@link FactorRiskReport#portfolioFactorBetas()}. This captures
 *       the sector/size tilt, not just market beta.</li>
 *   <li><b>Beta-only cross-check</b> — {@code β_mkt·indexShock·V}, where the index shock is the
 *       scenario's MKT (Nifty) return. A single-factor (MKT-only) portfolio makes the two
 *       estimates identical; their gap on a real book is the tilt and is reported, not hidden.</li>
 * </ul>
 *
 * <p>{@code V} is the market value of the <em>included</em> sub-portfolio (the holdings that
 * survived the factor model's history filter), so it is consistent with the renormalized betas.
 * Sector/SIZE shocks in the CSV are spreads (factor − Nifty) to match the spread-betas the
 * factor model estimates. Numbers are rendered by the card layer; this service only computes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StressScenarioService {

    private final FactorModelService factorModelService;
    private final InvestmentRepository investmentRepository;
    private final QuantitativeMacroState macroState;

    @Value("${cfo.stress.scenarios-path:classpath:data/stress_scenarios.csv}")
    private Resource scenariosResource;

    private List<Scenario> scenarios = List.of();
    private volatile byte[] rawScenariosCsv = new byte[0];

    /** One named scenario: an ordered factor → shock-percent map (e.g. MKT → −13.0). */
    record Scenario(String id, String label, Map<String, Double> factorShocks) {
        double mktShockPct() {
            return factorShocks.getOrDefault(FactorReturnService.MKT_FACTOR, 0.0);
        }
    }

    /** Per-scenario result for one user; all ₹ figures are signed (negative = loss). */
    public record StressResult(
            String scenario,
            String label,
            double niftyShockPct,                 // scenario MKT (Nifty) return, %
            double factorModelPnl,                // Σ β_f·shock_f·V  (₹)
            double betaOnlyPnl,                   // β_mkt·indexShock·V  (₹)
            double portfolioValue,                // V used = included sub-portfolio value (₹)
            double betaMkt,
            double lossPctOfValue,                // factorModelPnl / V  (negative = loss)
            Map<String, Double> appliedFactorShocks, // factors actually loaded on → shock %
            List<String> notes,
            boolean policyOverlayApplied,
            String overlayNotes
    ) {}

    @PostConstruct
    void init() {
        loadScenarios(scenariosResource);
    }

    /**
     * Parse the scenario CSV. Package-private and resource-parameterized so a unit test can
     * load the real classpath file deterministically without booting Spring.
     */
    void loadScenarios(Resource resource) {
        if (resource == null || !resource.exists()) {
            log.warn("[Stress] scenarios CSV not found; stress scenarios disabled");
            this.scenarios = List.of();
            return;
        }
        try {
            byte[] bytes = resource.getInputStream().readAllBytes();
            rawScenariosCsv = bytes;
            parseScenarioBytes(bytes);
        } catch (Exception e) {
            log.warn("[Stress] failed to load scenarios CSV: {}", e.getMessage());
        }
        log.info("[Stress] loaded {} stress scenarios", scenarios.size());
    }

    private void parseScenarioBytes(byte[] bytes) {
        Map<String, Scenario> byId = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8))) {
            reader.lines()
                    .map(String::trim)
                    .filter(line -> !line.isBlank() && !line.startsWith("#"))
                    .forEach(line -> parseLine(line, byId));
        } catch (Exception e) {
            log.warn("[Stress] failed to parse scenarios CSV bytes: {}", e.getMessage());
        }
        this.scenarios = List.copyOf(byId.values());
    }

    public synchronized void reloadScenarios(InputStream is) throws IOException {
        byte[] bytes = is.readAllBytes();
        parseScenarioBytes(bytes);
        rawScenariosCsv = bytes;
        log.info("[Stress] reloaded {} stress scenarios", scenarios.size());
    }

    public byte[] getRawScenariosCsv() {
        return rawScenariosCsv;
    }

    private void parseLine(String line, Map<String, Scenario> byId) {
        String[] parts = line.split(",", 4);
        if (parts.length < 4) return;
        String id = parts[0].trim();
        String label = parts[1].trim();
        String factor = parts[2].trim();
        double shockPct;
        try {
            shockPct = Double.parseDouble(parts[3].trim());
        } catch (NumberFormatException e) {
            log.warn("[Stress] bad shock value in row '{}'", line);
            return;
        }
        Scenario scenario = byId.computeIfAbsent(id, k -> new Scenario(id, label, new LinkedHashMap<>()));
        scenario.factorShocks().put(factor, shockPct);
    }

    /**
     * Stress the user's current book against every loaded scenario, worst factor-model loss first.
     * Degrades to an empty list (no card) when the factor model is unavailable, has no exposures,
     * or the included value is non-positive — never a fabricated number.
     */
    public List<StressResult> stress(String userId) {
        if (scenarios.isEmpty()) return List.of();

        Optional<FactorRiskReport> frOpt;
        try {
            frOpt = factorModelService.compute(userId);
        } catch (RuntimeException e) {
            log.warn("[Stress] factor model unavailable for {}: {}", userId, e.getMessage());
            return List.of();
        }
        if (frOpt.isEmpty()) return List.of();
        FactorRiskReport fr = frOpt.get();

        Map<String, Double> betas = fr.portfolioFactorBetas();
        if (betas == null || betas.isEmpty()) return List.of();

        double value = includedValue(userId, fr.includedSymbols());
        if (!(value > 0)) return List.of();

        double betaMkt = betas.getOrDefault(FactorReturnService.MKT_FACTOR, Double.NaN);
        List<String> baseNotes = fr.isLowConfidence()
                ? List.of("LOW_CONFIDENCE factor betas — stress estimate is indicative only")
                : List.of();

        Map<String, Double> policyShocks = macroState.getPolicyRateShocks();

        List<StressResult> out = new ArrayList<>();
        for (Scenario s : scenarios) {
            Map<String, Double> mutableShocks = new HashMap<>(s.factorShocks());
            boolean overlayApplied = false;
            StringBuilder overlayNotesSb = new StringBuilder();

            for (Map.Entry<String, Double> e : policyShocks.entrySet()) {
                String rawFactor = e.getKey().split(":")[0];
                if (mutableShocks.containsKey(rawFactor)) {
                    double csv = mutableShocks.get(rawFactor);
                    double overlay = e.getValue();
                    double scale = e.getKey().endsWith(":HIGH_SURPRISE") ? 1.5
                                 : e.getKey().endsWith(":LOW_SURPRISE")  ? 0.7 : 1.0;
                    double effective = Math.min(csv + overlay * scale, 0.0);
                    mutableShocks.put(rawFactor, effective);
                    overlayApplied = true;
                    overlayNotesSb.append(e.getKey()).append("=")
                        .append(String.format("%.1f%%", effective * 100)).append(" ");
                }
            }

            double pnl = 0.0;
            Map<String, Double> applied = new LinkedHashMap<>();
            for (Map.Entry<String, Double> e : mutableShocks.entrySet()) {
                Double beta = betas.get(e.getKey());
                if (beta == null) continue;                       // portfolio has no such exposure
                pnl += beta * (e.getValue() / 100.0) * value;
                applied.put(e.getKey(), e.getValue());
            }
            double niftyShockPct = s.mktShockPct();
            double betaOnlyPnl = Double.isNaN(betaMkt)
                    ? Double.NaN
                    : betaMkt * (niftyShockPct / 100.0) * value;

            out.add(new StressResult(s.id(), s.label(), niftyShockPct, pnl, betaOnlyPnl,
                    value, betaMkt, pnl / value, applied, baseNotes,
                    overlayApplied, overlayNotesSb.toString().trim()));
        }
        out.sort(Comparator.comparingDouble(StressResult::factorModelPnl)); // worst loss first
        return out;
    }

    /** Market value of the holdings the factor model actually included (consistent with B). */
    private double includedValue(String userId, List<String> includedSymbols) {
        if (includedSymbols == null || includedSymbols.isEmpty()) return 0.0;
        Set<String> included = includedSymbols.stream()
                .map(String::toUpperCase).collect(Collectors.toSet());
        return investmentRepository.findActiveInvestments(userId).stream()
                .filter(i -> i.getSymbol() != null && included.contains(i.getSymbol().toUpperCase()))
                .map(Investment::getCurrentValue)
                .filter(Objects::nonNull)
                .mapToDouble(BigDecimal::doubleValue)
                .sum();
    }
}
