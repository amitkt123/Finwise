package org.amit.finwise.marketdata.repository;

import org.amit.finwise.marketdata.model.Instrument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface InstrumentRepository extends JpaRepository<Instrument, Long> {

    Optional<Instrument> findByIsin(String isin);

    Optional<Instrument> findBySymbol(String symbol);
}
