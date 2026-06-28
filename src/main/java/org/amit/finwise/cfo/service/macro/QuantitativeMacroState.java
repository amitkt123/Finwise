package org.amit.finwise.cfo.service.macro;

import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.config.RiskProperties;
import org.amit.finwise.cfo.model.macro.MacroStateAuditEntry;
import org.amit.finwise.cfo.model.macro.MacroStateSnapshot;
import org.amit.finwise.cfo.repository.macro.MacroStateAuditRepository;
import org.amit.finwise.cfo.repository.macro.MacroStateSnapshotRepository;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Service @Slf4j
public class QuantitativeMacroState {
    private final MacroStateSnapshotRepository snapRepo;
    private final MacroStateAuditRepository auditRepo;
    private final RiskProperties riskProps;

    private final AtomicReference<Double> riskFreeRate = new AtomicReference<>(Double.NaN);
    private final AtomicReference<Double> crisisProbability = new AtomicReference<>(0.0);
    private final AtomicReference<Double> regimeVolCalm = new AtomicReference<>(Double.NaN);
    private final AtomicReference<Double> regimeVolCrisis = new AtomicReference<>(Double.NaN);
    private final AtomicReference<Double> yieldCurve10y = new AtomicReference<>(Double.NaN);
    private final AtomicReference<Double> yieldCurveSlope = new AtomicReference<>(Double.NaN);
    private final AtomicReference<Double> fiiFlowScore = new AtomicReference<>(0.0);
    private final Map<String, Double> policyRateShocks = new ConcurrentHashMap<>();

    public QuantitativeMacroState(MacroStateSnapshotRepository snapRepo,
                                   MacroStateAuditRepository auditRepo,
                                   RiskProperties riskProps) {
        this.snapRepo = snapRepo;
        this.auditRepo = auditRepo;
        this.riskProps = riskProps;
        loadFromSnapshot();
    }

    private void loadFromSnapshot() {
        snapRepo.findById("riskFreeRate").ifPresent(s -> riskFreeRate.set(s.getValue()));
        snapRepo.findById("crisisProbability").ifPresent(s -> crisisProbability.set(s.getValue()));
        snapRepo.findById("regimeVolCalm").ifPresent(s -> regimeVolCalm.set(s.getValue()));
        snapRepo.findById("regimeVolCrisis").ifPresent(s -> regimeVolCrisis.set(s.getValue()));
        snapRepo.findById("yieldCurve10y").ifPresent(s -> yieldCurve10y.set(s.getValue()));
        snapRepo.findById("yieldCurveSlope").ifPresent(s -> yieldCurveSlope.set(s.getValue()));
        snapRepo.findById("fiiFlowScore").ifPresent(s -> fiiFlowScore.set(s.getValue()));
    }

    public double getRiskFreeRate() {
        double v = riskFreeRate.get();
        return Double.isNaN(v) ? riskProps.getRiskFreeRate() : v;
    }
    public double getCrisisProbability() { return crisisProbability.get(); }
    public double getRegimeVolCalm()     { return regimeVolCalm.get(); }
    public double getRegimeVolCrisis()   { return regimeVolCrisis.get(); }
    public double getYieldCurve10y()     { return yieldCurve10y.get(); }
    public double getYieldCurveSlope()   { return yieldCurveSlope.get(); }
    public double getFiiFlowScore()      { return fiiFlowScore.get(); }
    public Map<String, Double> getPolicyRateShocks() {
        return Collections.unmodifiableMap(policyRateShocks);
    }

    public void setRiskFreeRate(double v, String source)        { write("riskFreeRate", riskFreeRate, v, source); }
    public void setCrisisProbability(double v, String source)   { write("crisisProbability", crisisProbability, v, source); }
    public void setRegimeVolCalm(double v, String source)       { write("regimeVolCalm", regimeVolCalm, v, source); }
    public void setRegimeVolCrisis(double v, String source)     { write("regimeVolCrisis", regimeVolCrisis, v, source); }
    public void setYieldCurve10y(double v, String source)       { write("yieldCurve10y", yieldCurve10y, v, source); }
    public void setYieldCurveSlope(double v, String source)     { write("yieldCurveSlope", yieldCurveSlope, v, source); }
    public void setFiiFlowScore(double v, String source)        { write("fiiFlowScore", fiiFlowScore, v, source); }
    public void putPolicyRateShock(String key, double v)        { policyRateShocks.put(key, v); }

    private void write(String field, AtomicReference<Double> ref, double newVal, String source) {
        double old = ref.getAndSet(newVal);
        snapRepo.save(MacroStateSnapshot.builder()
            .fieldName(field).value(newVal).source(source).lastConfirmedBy(source).build());
        auditRepo.save(MacroStateAuditEntry.builder()
            .fieldName(field).oldValue(Double.isNaN(old) ? 0.0 : old)
            .newValue(newVal).source(source).confirmedBy(source).build());
        log.info("[MacroState] {} {} -> {} (source={})", field, old, newVal, source);
    }

    public List<MacroStateAuditEntry> getAuditLog() {
        return auditRepo.findTop100ByOrderByCreatedAtDesc();
    }
}
