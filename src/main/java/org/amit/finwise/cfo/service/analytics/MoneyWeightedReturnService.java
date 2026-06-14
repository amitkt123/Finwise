package org.amit.finwise.cfo.service.analytics;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.PortfolioSnapshot;
import org.amit.finwise.cfo.model.Transaction;
import org.amit.finwise.cfo.repository.PortfolioSnapshotRepository;
import org.amit.finwise.cfo.repository.TransactionRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Money-Weighted Rate of Return — XIRR over irregular cash flows.
 *
 * Where {@link PortfolioPerformanceService} computes TWRR (which strips out the
 * timing/size of contributions, the SEBI-mandated skill measure), XIRR answers
 * the question a SIP-heavy retail investor actually asks: "what return did *my
 * rupees* earn?". It is the IRR of the dated cash-flow vector, annualised.
 *
 * Cash-flow convention (investor's perspective):
 *   BUY  → −amount  (money leaves the pocket)
 *   SELL → +amount  (money returns)
 *   terminal market value → +V on today (as if liquidated now)
 *
 * Solve for r in:  Σ CFᵢ · (1+r)^(−dᵢ/365) = 0
 *
 * Newton-Raphson seeded at 0.1 with an analytic derivative; bisection fallback on
 * [-0.9999, 10] when Newton fails to converge or the derivative vanishes. A
 * sign-change in the all-flows-discounted NPV is guaranteed for any cash-flow
 * vector with at least one inflow and one outflow, so the bracket always brackets
 * a root. The degenerate all-same-sign case returns empty.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MoneyWeightedReturnService {

    private final PortfolioSnapshotRepository snapshotRepository;
    private final TransactionRepository transactionRepository;

    private static final double SEED = 0.10;
    private static final int NEWTON_MAX_ITERS = 100;
    private static final double NEWTON_TOL = 1e-9;           // NPV tolerance
    private static final double DERIV_FLOOR = 1e-10;         // below this, bail to bisection
    private static final double RATE_LOW = -0.9999;          // -100% return floor
    private static final double RATE_HIGH = 10.0;            // +1000% annualised ceiling
    private static final int BISECTION_MAX_ITERS = 200;
    private static final double BISECTION_TOL = 1e-10;

    /** A single dated cash flow (amount signed per investor convention). */
    public record CashFlow(LocalDate date, double amount) {}

    public record XirrResult(
            double xirr,             // annualised money-weighted return (e.g. 0.124 = 12.4%)
            double absoluteMwr,      // simple (non-annualised) return: netInflow / capitalDeployed
            LocalDate from,
            LocalDate to,
            int cashFlowCount,
            double terminalValue,
            boolean converged,       // false → solver fell back / failed; treat as indicative
            String method            // "newton" | "bisection" | "none"
    ) {}

    /**
     * Computes portfolio XIRR for a user from BUY/SELL transactions plus the
     * latest snapshot market value as the terminal flow. Empty when there are
     * insufficient flows, no terminal value, or the flows are all one sign.
     */
    public Optional<XirrResult> computeXirr(String userId) {
        Optional<PortfolioSnapshot> latest =
                snapshotRepository.findTopByUserIdOrderBySnapshotTimeDesc(userId);
        if (latest.isEmpty() || latest.get().getCurrentValue() == null) {
            log.debug("[XIRR] No terminal snapshot value for {}", userId);
            return Optional.empty();
        }
        double terminalValue = latest.get().getCurrentValue().doubleValue();
        LocalDate terminalDate = latest.get().getSnapshotTime().toLocalDate();

        List<CashFlow> flows = new ArrayList<>();
        for (Transaction t : transactionRepository.findBuySellTransactionsAsc(userId)) {
            if (t.getAmount() == null || t.getTransactionDate() == null) continue;
            double amt = t.getAmount().doubleValue();
            double signed = t.getTransactionType() == Transaction.TransactionType.BUY ? -amt : amt;
            flows.add(new CashFlow(t.getTransactionDate(), signed));
        }
        if (flows.isEmpty()) return Optional.empty();

        // Terminal liquidation as a positive flow on the snapshot date.
        if (terminalValue > 0) {
            flows.add(new CashFlow(terminalDate, terminalValue));
        }

        return solve(flows);
    }

    /**
     * Solves XIRR for an arbitrary dated cash-flow vector. Public so the
     * calibration loop (BPR-10) can reuse the same solver on holding-period flows.
     */
    public Optional<XirrResult> solve(List<CashFlow> flows) {
        if (flows == null || flows.size() < 2) return Optional.empty();

        // Need both an inflow and an outflow, else no root exists.
        boolean hasNeg = false, hasPos = false;
        for (CashFlow cf : flows) {
            if (cf.amount() < 0) hasNeg = true;
            if (cf.amount() > 0) hasPos = true;
        }
        if (!(hasNeg && hasPos)) {
            log.debug("[XIRR] Degenerate all-same-sign cash flows — no IRR");
            return Optional.empty();
        }

        LocalDate t0 = flows.stream().map(CashFlow::date).min(LocalDate::compareTo).orElseThrow();
        LocalDate tN = flows.stream().map(CashFlow::date).max(LocalDate::compareTo).orElseThrow();

        // Precompute year fractions from the first date.
        double[] amounts = new double[flows.size()];
        double[] years = new double[flows.size()];
        double deployed = 0, returned = 0, terminal = 0;
        for (int i = 0; i < flows.size(); i++) {
            CashFlow cf = flows.get(i);
            amounts[i] = cf.amount();
            years[i] = ChronoUnit.DAYS.between(t0, cf.date()) / 365.0;
            if (cf.amount() < 0) deployed += -cf.amount();
            else returned += cf.amount();
        }
        terminal = flows.getLast().amount();
        double absoluteMwr = deployed > 0 ? (returned - deployed) / deployed : Double.NaN;

        // --- Newton-Raphson with analytic derivative ---
        double r = SEED;
        boolean newtonOk = false;
        for (int iter = 0; iter < NEWTON_MAX_ITERS; iter++) {
            double npv = npv(amounts, years, r);
            if (Math.abs(npv) < NEWTON_TOL) { newtonOk = true; break; }
            double d = dNpv(amounts, years, r);
            if (Math.abs(d) < DERIV_FLOOR) break;          // flat — abandon Newton
            double next = r - npv / d;
            if (next <= RATE_LOW) next = RATE_LOW + 1e-6;  // stay inside the domain
            if (Double.isNaN(next) || Double.isInfinite(next)) break;
            if (Math.abs(next - r) < NEWTON_TOL) { r = next; newtonOk = true; break; }
            r = next;
        }

        if (newtonOk && r > RATE_LOW && !Double.isNaN(r)) {
            return Optional.of(new XirrResult(r, absoluteMwr, t0, tN, flows.size(),
                    terminal, true, "newton"));
        }

        // --- Bisection fallback on a guaranteed sign-change bracket ---
        Optional<Double> bis = bisect(amounts, years);
        if (bis.isPresent()) {
            return Optional.of(new XirrResult(bis.get(), absoluteMwr, t0, tN, flows.size(),
                    terminal, true, "bisection"));
        }

        log.debug("[XIRR] Solver did not converge for {} flows", flows.size());
        return Optional.empty();
    }

    private static double npv(double[] amounts, double[] years, double r) {
        double base = 1.0 + r;
        double sum = 0;
        for (int i = 0; i < amounts.length; i++) {
            sum += amounts[i] * Math.pow(base, -years[i]);
        }
        return sum;
    }

    /** d/dr Σ CFᵢ (1+r)^(−yᵢ) = Σ −yᵢ·CFᵢ (1+r)^(−yᵢ−1). */
    private static double dNpv(double[] amounts, double[] years, double r) {
        double base = 1.0 + r;
        double sum = 0;
        for (int i = 0; i < amounts.length; i++) {
            sum += -years[i] * amounts[i] * Math.pow(base, -years[i] - 1.0);
        }
        return sum;
    }

    private Optional<Double> bisect(double[] amounts, double[] years) {
        double lo = RATE_LOW, hi = RATE_HIGH;
        double fLo = npv(amounts, years, lo);
        double fHi = npv(amounts, years, hi);
        if (Double.isNaN(fLo) || Double.isNaN(fHi)) return Optional.empty();
        if (fLo == 0) return Optional.of(lo);
        if (fHi == 0) return Optional.of(hi);
        if (fLo * fHi > 0) {
            // No sign change on the default bracket — root is outside [lo,hi].
            return Optional.empty();
        }
        for (int i = 0; i < BISECTION_MAX_ITERS; i++) {
            double mid = 0.5 * (lo + hi);
            double fMid = npv(amounts, years, mid);
            if (Math.abs(fMid) < BISECTION_TOL || (hi - lo) < BISECTION_TOL) {
                return Optional.of(mid);
            }
            if (fLo * fMid < 0) {
                hi = mid;
            } else {
                lo = mid;
                fLo = fMid;
            }
        }
        return Optional.of(0.5 * (lo + hi));
    }
}
