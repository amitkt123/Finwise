package org.amit.finwise.marketdata.repository;

import org.amit.finwise.marketdata.model.Announcement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {

    List<Announcement> findBySymbolOrderByBroadcastAtDesc(String symbol);

    List<Announcement> findByBroadcastAtAfterOrderByBroadcastAtDesc(LocalDateTime since);
}
