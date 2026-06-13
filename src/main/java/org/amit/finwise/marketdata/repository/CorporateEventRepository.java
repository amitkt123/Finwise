package org.amit.finwise.marketdata.repository;

import org.amit.finwise.marketdata.model.CorporateEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CorporateEventRepository extends JpaRepository<CorporateEvent, Long> {
}
