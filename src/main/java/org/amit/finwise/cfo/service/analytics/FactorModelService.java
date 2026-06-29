package org.amit.finwise.cfo.service.analytics;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.config.FactorProperties;
import org.amit.finwise.cfo.model.FactorRiskReport;
import org.amit.finwise.cfo.model.FactorRiskReport.HoldingFactorExposure;
import org.amit.finwise.cfo.service.ingestion.SymbolExtractorService;
import org.amit.finwise.cfo.service.macro.QuantitativeMacroState;
import org.amit.finwise.investment.model.Investment;
import org.amit.finwise.investment.repository.InvestmentRepository;
import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.LUDecomposition;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.stat.StatUtils;
import org.apache.commons.math3.stat.regression.OLSMultipleLinearRegression;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Index-based multi-factor risk model (Phase 8).
 *
 * Per holding: OLS of daily returns on MKT, SIZE, and the holding's OWN sector
 * spread — exactly those regressors, never all sector indices at once (sector
 * spreads correlate with each other; own-sector-only avoids multicollinearity).
 * Holdings with fewer than {@code cfo.factors.min-observations} aligned days are
 * excluded with a data-quality note, mirroring the covariance-engine convention.
 *
 * Portfolio level: B = Σ wᵢβᵢ per factor; systematic variance BᵀΣ_F B with the
 * factor covariance Σ_F through Ledoit-Wolf shrinkage (≥3 factors); idiosyncratic
 * variance Σ wᵢ²σ²_ε,ᵢ. The model total is reported NEXT TO the direct wᵀΣw
 * portfolio variance — both are estimates and the gap is informative, so they
 * are never forced to reconcile.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FactorModelService {

    private final ReturnSeriesService returnSeriesService;
    private final FactorReturnService factorReturnService;
    private final CovarianceEngine covarianceEngine;
    private final InvestmentRepository investmentRepository;
    private final SymbolExtractorService symbolExtractorService;
    private final FactorProperties factorProperties;
    private final KalmanBetaService kalmanBetaService;
    private final QuantitativeMacroState macroState;

    private static final double ANNUAL_DAYS = 252.0;
    private static final double SQRT_252 = Math.sqrt(ANNUAL_DAYS);

    public Optional<FactorRiskReport> compute(String userId) {
        List<Investment> equities = investmentRepository.findActiveInvestments(userId).stream()
                .filter(inv -> inv.getSymbol() != null && !inv.getSymbol().isBlank())
                .filter(inv -> inv.getCurrentValue() != null
                        && inv.getCurrentValue().compareTo(BigDecimal.ZERO) > 0)
                .toList();
        if (equities.isEmpty()) return Optional.empty();

        BigDecimal totalValue = equities.stream()
                .map(Investment::getCurrentValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalValue.compareTo(BigDecimal.ZERO) == 0) return Optional.empty();

        Map<String, Double> rawWeights = new LinkedHashMap<>();
        Map<String, String> fallbackSector = new HashMap<>();
        for (Investment inv : equities) {
            String sym = inv.getSymbol().toUpperCase();
            rawWeights.merge(sym, inv.getCurrentValue().doubleValue() / totalValue.doubleValue(),
                    Double::sum);
            if (inv.getSector() != null) fallbackSector.put(sym, inv.getSector());
        }

        LocalDate since = LocalDate.now().minusDays(factorProperties.getLookbackDays());

        Optional<FactorReturnService.FactorSet> factorsOpt = factorReturnService.build(since);
        if (factorsOpt.isEmpty()) return Optional.empty();
        FactorReturnService.FactorSet factors = factorsOpt.get();

        List<String> dataQualityNotes = new ArrayList<>();
        // BPR-6: prefer true Fama-French SMB/HML over the circular own-sector + index-spread
        // SIZE factors when the fundamentals cross-section is deep enough.
        boolean useFamaFrench = factors.hasFamaFrench();
        if (useFamaFrench) {
            dataQualityNotes.add(factors.famaFrenchNote() != null ? factors.famaFrenchNote()
                    : "FAMA_FRENCH: regressing on MKT + SMB + HML (cross-sectional style factors)");
        } else {
            dataQualityNotes.add("FAMA_FRENCH_FALLBACK: true SMB/HML unavailable (fundamentals thin) "
                    + "— using index-spread SIZE + own-sector proxy factors");
            if (factors.size() == null) {
                dataQualityNotes.add("SIZE_FACTOR_UNAVAILABLE: no " + factorProperties.getMidcapIndex()
                        + " history — regressions use MKT + sector only");
            }
        }

        Map<String, NavigableMap<LocalDate, Double>> stockReturns =
                returnSeriesService.getReturnSeries(new ArrayList<>(rawWeights.keySet()), since);

        // ── Per-holding regressions ───────────────────────────────────────────
        int minObs = factorProperties.getMinObservations();
        List<HoldingFit> fits = new ArrayList<>();
        List<String> excluded = new ArrayList<>();

        for (String sym : rawWeights.keySet()) {
            NavigableMap<LocalDate, Double> series = stockReturns.get(sym);
            if (series == null) {
                excluded.add(sym);
                dataQualityNotes.add("FACTOR_EXCLUDED: " + sym + " — insufficient price history");
                continue;
            }

            String sector = resolveSector(sym, fallbackSector);

            // Regressor set for THIS holding, in canonical order.
            List<String> names = new ArrayList<>();
            List<NavigableMap<LocalDate, Double>> regressors = new ArrayList<>();
            names.add(FactorReturnService.MKT_FACTOR);
            regressors.add(factors.mkt());

            String sectorIdx = null;
            if (useFamaFrench) {
                // True style factors — no circular own-sector regressor.
                names.add(FactorReturnService.SMB_FACTOR);
                regressors.add(factors.smb());
                names.add(FactorReturnService.HML_FACTOR);
                regressors.add(factors.hml());
            } else {
                sectorIdx = factorProperties.indexForSector(sector).orElse(null);
                NavigableMap<LocalDate, Double> sectorSpread =
                        sectorIdx != null ? factors.sectorSpreads().get(sectorIdx) : null;
                if (sectorIdx != null && sectorSpread == null) {
                    dataQualityNotes.add("SECTOR_FACTOR_MISSING: " + sym + " sector '" + sector
                            + "' maps to " + sectorIdx + " but the index has no return history — MKT"
                            + (factors.size() != null ? "+SIZE" : "") + " only");
                    sectorIdx = null;
                }
                if (factors.size() != null) {
                    names.add(FactorReturnService.SIZE_FACTOR);
                    regressors.add(factors.size());
                }
                if (sectorIdx != null) {
                    names.add(sectorIdx);
                    regressors.add(sectorSpread);
                }
            }

            HoldingFit fit = regress(sym, sector, sectorIdx, series, names, regressors, minObs);
            if (fit == null) {
                excluded.add(sym);
                dataQualityNotes.add("FACTOR_EXCLUDED: " + sym + " — fewer than " + minObs
                        + " observations aligned with factor series (or singular regression)");
            } else {
                fits.add(fit);
            }
        }

        if (fits.isEmpty()) {
            log.warn("[FactorModel] No holdings survived the {}-obs alignment filter", minObs);
            return Optional.empty();
        }

        // ── Weights renormalized to included holdings; dropped weight reported ──
        double includedTotalWeight = fits.stream()
                .mapToDouble(f -> rawWeights.getOrDefault(f.symbol, 0.0)).sum();
        double excludedWeightPct = Math.max(0.0, 1.0 - includedTotalWeight);
        if (!excluded.isEmpty()) {
            dataQualityNotes.add(String.format(
                    "EXCLUDED_WEIGHT: %.1f%% of portfolio value not in the factor model (%s)",
                    excludedWeightPct * 100, String.join(", ", excluded)));
        }
        Map<String, Double> weights = fits.stream().collect(Collectors.toMap(
                f -> f.symbol,
                f -> rawWeights.getOrDefault(f.symbol, 0.0) / includedTotalWeight,
                (a, _) -> a, LinkedHashMap::new));

        // ── Portfolio factor exposures B_k = Σ wᵢ βᵢₖ ───────────────────────────
        // Canonical factor order: MKT, SIZE, then sector tickers actually used.
        List<String> factorNames = new ArrayList<>();
        factorNames.add(FactorReturnService.MKT_FACTOR);
        if (useFamaFrench) {
            factorNames.add(FactorReturnService.SMB_FACTOR);
            factorNames.add(FactorReturnService.HML_FACTOR);
        } else {
            if (factors.size() != null) factorNames.add(FactorReturnService.SIZE_FACTOR);
            fits.stream().map(f -> f.sectorFactor).filter(Objects::nonNull)
                    .collect(Collectors.toCollection(TreeSet::new))
                    .forEach(factorNames::add);
        }

        Map<String, Double> portfolioBetas = new LinkedHashMap<>();
        for (String factor : factorNames) {
            double b = 0;
            for (HoldingFit f : fits) {
                Double beta = f.betas.get(factor);
                if (beta != null) b += weights.get(f.symbol) * beta;
            }
            portfolioBetas.put(factor, b);
        }

        // ── Factor covariance Σ_F (Ledoit-Wolf when ≥3 factors, as in Phase 2) ──
        Map<String, NavigableMap<LocalDate, Double>> factorSeries = new LinkedHashMap<>();
        for (String factor : factorNames) {
            factorSeries.put(factor, switch (factor) {
                case FactorReturnService.MKT_FACTOR -> factors.mkt();
                case FactorReturnService.SIZE_FACTOR -> factors.size();
                case FactorReturnService.SMB_FACTOR -> factors.smb();
                case FactorReturnService.HML_FACTOR -> factors.hml();
                default -> factors.sectorSpreads().get(factor);
            });
        }
        CovarianceEngine.AlignedSeries alignedFactors = covarianceEngine.align(factorSeries, minObs);

        double systematicVolAnn = Double.NaN, idioVolAnn = Double.NaN, totalVolAnn = Double.NaN;
        double systematicSharePct = Double.NaN;
        Map<String, Double> factorVarianceSharePct = new LinkedHashMap<>();
        if (alignedFactors != null) {
            double[][] sigmaF = factorNames.size() >= 3
                    ? covarianceEngine.shrunkCovariance(alignedFactors).sigma()
                    : covarianceEngine.covarianceMatrix(alignedFactors);

            int k = alignedFactors.symbols().size();
            double[] B = new double[k];
            for (int i = 0; i < k; i++) B[i] = portfolioBetas.get(alignedFactors.symbols().get(i));

            double[] sigmaFB = new double[k];
            for (int i = 0; i < k; i++) {
                for (int j = 0; j < k; j++) sigmaFB[i] += sigmaF[i][j] * B[j];
            }
            double sysVarDaily = 0;
            for (int i = 0; i < k; i++) sysVarDaily += B[i] * sigmaFB[i];

            double idioVarDaily = 0;
            for (HoldingFit f : fits) {
                double wi = weights.get(f.symbol);
                idioVarDaily += wi * wi * f.errVarDaily;
            }

            double totalVarDaily = sysVarDaily + idioVarDaily;
            systematicVolAnn = Math.sqrt(Math.max(0, sysVarDaily) * ANNUAL_DAYS);
            idioVolAnn = Math.sqrt(idioVarDaily * ANNUAL_DAYS);
            totalVolAnn = Math.sqrt(totalVarDaily * ANNUAL_DAYS);
            systematicSharePct = totalVarDaily > 0 ? sysVarDaily / totalVarDaily * 100 : Double.NaN;

            // Contribution of factor k to systematic variance: B_k(Σ_F B)_k / BᵀΣ_F B.
            // Shares sum to 100 but an individual share can be slightly negative
            // (negative covariance cross-terms) — that is informative, keep it.
            for (int i = 0; i < k; i++) {
                factorVarianceSharePct.put(alignedFactors.symbols().get(i),
                        sysVarDaily > 0 ? B[i] * sigmaFB[i] / sysVarDaily * 100 : Double.NaN);
            }
        } else {
            dataQualityNotes.add("FACTOR_COV_UNAVAILABLE: fewer than " + minObs
                    + " common dates across factor series — systematic/idio split not computed");
        }

        // ── Direct wᵀΣw reconciliation figure on the same lookback ─────────────
        double directVolAnn = directPortfolioVol(stockReturns, weights, minObs);

        LocalDate from = fits.stream().map(f -> f.from).min(LocalDate::compareTo).orElse(null);
        LocalDate to = fits.stream().map(f -> f.to).max(LocalDate::compareTo).orElse(null);

        boolean isLowConfidence = excludedWeightPct > 0.25
                || factors.size() == null
                || alignedFactors == null;

        List<HoldingFactorExposure> holdings = fits.stream()
                .map(f -> {
                    var kalman = kalmanBetaService.fit(f.assetRet, f.factorRet,
                            macroState.getCrisisProbability());
                    double olsBetaMkt = f.betas.getOrDefault(FactorReturnService.MKT_FACTOR, 0.0);
                    double kalmanBeta = kalman.currentBeta().length > 0
                            ? kalman.currentBeta()[0] : olsBetaMkt;
                    double betaDrift = kalman.betaDrift();
                    return new HoldingFactorExposure(
                            f.symbol, weights.get(f.symbol), f.sector, f.sectorFactor,
                            olsBetaMkt,
                            f.betas.get(FactorReturnService.SIZE_FACTOR),
                            f.sectorFactor != null ? f.betas.get(f.sectorFactor) : null,
                            Collections.unmodifiableMap(f.tStats),
                            f.alphaDaily * ANNUAL_DAYS,
                            f.rSquared,
                            Math.sqrt(f.errVarDaily) * SQRT_252,
                            f.observations,
                            f.hacLag,
                            kalmanBeta,
                            betaDrift);
                })
                .sorted(Comparator.comparingDouble(HoldingFactorExposure::weight).reversed())
                .toList();

        String headline = buildHeadline(portfolioBetas, systematicSharePct, factorVarianceSharePct,
                idioVolAnn);

        log.info("[FactorModel] {} holdings, {} factors: B_mkt={}, systematic={}%, idio vol={}%",
                fits.size(), factorNames.size(),
                String.format("%.2f", portfolioBetas.get(FactorReturnService.MKT_FACTOR)),
                Double.isNaN(systematicSharePct) ? "n/a" : String.format("%.0f", systematicSharePct),
                Double.isNaN(idioVolAnn) ? "n/a" : String.format("%.1f", idioVolAnn * 100));

        return Optional.of(new FactorRiskReport(
                fits.stream().map(f -> f.symbol).toList(),
                excluded,
                excludedWeightPct,
                Collections.unmodifiableList(dataQualityNotes),
                isLowConfidence,
                from, to,
                factorNames,
                Collections.unmodifiableMap(portfolioBetas),
                systematicVolAnn, idioVolAnn, totalVolAnn, systematicSharePct,
                Collections.unmodifiableMap(factorVarianceSharePct),
                directVolAnn,
                holdings,
                headline
        ));
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private String resolveSector(String symbol, Map<String, String> fallbackSector) {
        SymbolExtractorService.SymbolEntry entry = symbolExtractorService.getSymbolEntry(symbol);
        if (entry != null && entry.sector != null && !entry.sector.isBlank()) return entry.sector;
        return fallbackSector.get(symbol);
    }

    /**
     * OLS of the holding's returns on the given regressors over their common dates.
     * Returns null when fewer than minObs dates align or the design matrix is singular.
     */
    private HoldingFit regress(String symbol, String sector, String sectorFactor,
                               NavigableMap<LocalDate, Double> stockSeries,
                               List<String> factorNames,
                               List<NavigableMap<LocalDate, Double>> regressors,
                               int minObs) {
        SortedSet<LocalDate> common = new TreeSet<>(stockSeries.keySet());
        for (NavigableMap<LocalDate, Double> r : regressors) common.retainAll(r.keySet());
        if (common.size() < minObs) return null;

        int T = common.size();
        int k = regressors.size();
        double[] y = new double[T];
        double[][] x = new double[T][k];
        int t = 0;
        for (LocalDate d : common) {
            y[t] = stockSeries.get(d);
            for (int j = 0; j < k; j++) x[t][j] = regressors.get(j).get(d);
            t++;
        }

        try {
            OLSMultipleLinearRegression ols = new OLSMultipleLinearRegression();
            ols.newSampleData(y, x);
            double[] b = ols.estimateRegressionParameters();          // [α, β₁..βₖ]

            // Newey-West HAC standard errors (heteroskedasticity- and
            // autocorrelation-consistent). Daily factor residuals are routinely
            // serially correlated, which biases OLS SE downward; HAC fixes the t.
            int lag = hacLag(T);
            double[] se = neweyWestStandardErrors(x, ols.estimateResiduals(), lag);

            Map<String, Double> betas = new LinkedHashMap<>();
            Map<String, Double> tStats = new LinkedHashMap<>();
            for (int j = 0; j < k; j++) {
                betas.put(factorNames.get(j), b[j + 1]);
                tStats.put(factorNames.get(j), se[j + 1] > 0 ? b[j + 1] / se[j + 1] : Double.NaN);
            }

            double[][] factorRetT = new double[k][T];
            for (int ti = 0; ti < T; ti++)
                for (int j = 0; j < k; j++) factorRetT[j][ti] = x[ti][j];

            return new HoldingFit(symbol, sector, sectorFactor, betas, tStats,
                    b[0], ols.calculateRSquared(), ols.estimateErrorVariance(),
                    T, lag, common.getFirst(), common.getLast(), y, factorRetT);
        } catch (Exception e) {
            log.warn("[FactorModel] Regression failed for {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    /** Automatic Bartlett-kernel bandwidth: L = ⌊4·(T/100)^(2/9)⌋ (Newey-West 1994). */
    static int hacLag(int T) {
        return (int) Math.floor(4.0 * Math.pow(T / 100.0, 2.0 / 9.0));
    }

    /**
     * Newey-West HAC standard errors for the OLS parameter vector [α, β₁..βₖ].
     *
     * V = (ZᵀZ)⁻¹ · S · (ZᵀZ)⁻¹, where Z is the design matrix WITH an intercept
     * column and the "meat" S is the long-run residual covariance
     *   S = Ω₀ + Σ_{l=1}^{L} w_l (Ω_l + Ω_lᵀ),  Ω_l = Σ_{t>l} u_t u_{t-l} z_t z_{t-l}ᵀ
     * with Bartlett weights w_l = 1 − l/(L+1). L=0 reproduces White's HC0. SE is
     * the square root of the diagonal of V, in the same order as the OLS params.
     */
    static double[] neweyWestStandardErrors(double[][] x, double[] residuals, int lag) {
        int T = x.length;
        int p = x[0].length + 1;                  // +1 for the intercept column

        double[][] z = new double[T][p];
        for (int t = 0; t < T; t++) {
            z[t][0] = 1.0;
            System.arraycopy(x[t], 0, z[t], 1, p - 1);
        }

        double[][] s = new double[p][p];
        for (int t = 0; t < T; t++) {
            double u2 = residuals[t] * residuals[t];
            for (int a = 0; a < p; a++)
                for (int c = 0; c < p; c++)
                    s[a][c] += u2 * z[t][a] * z[t][c];
        }
        for (int l = 1; l <= lag; l++) {
            double w = 1.0 - (double) l / (lag + 1);
            for (int t = l; t < T; t++) {
                double uu = residuals[t] * residuals[t - l];
                for (int a = 0; a < p; a++)
                    for (int c = 0; c < p; c++)
                        s[a][c] += w * uu * (z[t][a] * z[t - l][c] + z[t - l][a] * z[t][c]);
            }
        }

        RealMatrix Z = new Array2DRowRealMatrix(z, false);
        RealMatrix bread = new LUDecomposition(Z.transpose().multiply(Z)).getSolver().getInverse();
        RealMatrix v = bread.multiply(new Array2DRowRealMatrix(s, false)).multiply(bread);

        double[] se = new double[p];
        for (int j = 0; j < p; j++) se[j] = Math.sqrt(Math.max(0.0, v.getEntry(j, j)));
        return se;
    }

    /** Annualized vol of the included-weights portfolio return series — the wᵀΣw figure. */
    private double directPortfolioVol(Map<String, NavigableMap<LocalDate, Double>> stockReturns,
                                      Map<String, Double> weights, int minObs) {
        Map<String, NavigableMap<LocalDate, Double>> included = new LinkedHashMap<>();
        for (String sym : weights.keySet()) {
            NavigableMap<LocalDate, Double> s = stockReturns.get(sym);
            if (s != null) included.put(sym, s);
        }
        CovarianceEngine.AlignedSeries aligned = covarianceEngine.align(included, minObs);
        if (aligned == null) return Double.NaN;

        int T = aligned.dates().size();
        double[] portfolio = new double[T];
        for (int t = 0; t < T; t++) {
            for (int j = 0; j < aligned.symbols().size(); j++) {
                portfolio[t] += weights.getOrDefault(aligned.symbols().get(j), 0.0)
                        * aligned.data()[t][j];
            }
        }
        return Math.sqrt(StatUtils.variance(portfolio)) * SQRT_252;
    }

    private static String buildHeadline(Map<String, Double> portfolioBetas,
                                        double systematicSharePct,
                                        Map<String, Double> factorVarianceSharePct,
                                        double idioVolAnn) {
        double mktBeta = portfolioBetas.getOrDefault(FactorReturnService.MKT_FACTOR, Double.NaN);
        if (Double.isNaN(systematicSharePct)) {
            return String.format("Factor exposures estimated (market beta %.2f) but the "
                    + "systematic/idiosyncratic split is unavailable", mktBeta);
        }
        String topFactor = factorVarianceSharePct.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(e -> String.format("%s drives %.0f%% of systematic risk", e.getKey(), e.getValue()))
                .orElse("");
        return String.format(
                "%.0f%% of portfolio variance is systematic (market beta %.2f; %s); "
                        + "idiosyncratic vol %.1f%% p.a.",
                systematicSharePct, mktBeta, topFactor, idioVolAnn * 100);
    }

    /** Per-holding regression output, pre-aggregation. */
    private record HoldingFit(
            String symbol, String sector, String sectorFactor,
            Map<String, Double> betas, Map<String, Double> tStats,
            double alphaDaily, double rSquared, double errVarDaily,
            int observations, int hacLag, LocalDate from, LocalDate to,
            double[] assetRet,      // aligned asset returns [t]
            double[][] factorRet    // aligned factor returns [factor][t]
    ) {}
}
