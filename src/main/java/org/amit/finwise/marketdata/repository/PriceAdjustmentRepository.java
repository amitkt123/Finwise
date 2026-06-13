package org.amit.finwise.marketdata.repository;

import org.amit.finwise.marketdata.model.PriceAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PriceAdjustmentRepository extends JpaRepository<PriceAdjustment, Long> {

    List<PriceAdjustment> findByInstrumentIdOrderByExDate(Long instrumentId);
}
