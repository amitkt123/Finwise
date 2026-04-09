package org.amit.finwise.cfo.service.ingestion;

import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.config.NewsProperties;
import org.amit.finwise.cfo.model.NewsArticle;
import org.amit.finwise.cfo.repository.NewsArticleRepository;
import org.amit.finwise.cfo.repository.TransactionRepository;
import org.amit.finwise.cfo.service.EmbeddingService;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URL;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class NewsAggregatorService {

    private final NewsArticleRepository newsArticleRepository;
    private final TransactionRepository transactionRepository;
    private final NewsClassificationPipeline classificationPipeline;
    private final SymbolExtractorService symbolExtractorService;
    private final NewsProperties newsProperties;
    private final EmbeddingService embeddingService;

    @Value("${cfo.user.id}")
    private String defaultUserId;

    /**
     * Fetch all news sources and store with relevance ranking.
     */
    @Transactional
    public int fetchAndStoreNews() {
        Set<String> userSymbols = getUserHoldingSymbols();
        int totalSaved = 0;

        for (Map.Entry<String, String> source : newsProperties.getSources().entrySet()) {
            totalSaved += fetchFromRss(source.getKey(), source.getValue(),
                    NewsArticle.Category.MARKET, userSymbols);
        }

        log.info("News fetch complete: {} new articles stored", totalSaved);
        return totalSaved;
    }

    /**
     * Get today's top news sorted by relevance (for CFO brief context).
     */
    public List<NewsArticle> getTodaysTopNews(int limit) {
        LocalDate today = LocalDate.now().minusDays(1); // include yesterday for early morning
        return newsArticleRepository.findRecentByRelevance(today)
                .stream()
                .limit(limit)
                .collect(Collectors.toList());
    }

    public List<NewsArticle> getNews(int daysBack, int limit) {
        return newsArticleRepository.findRecentByRelevance(LocalDate.now().minusDays(daysBack))
                .stream()
                .limit(limit)
                .collect(Collectors.toList());
    }

    // ── RSS Fetching ──────────────────────────────────────────────────────────

    private int fetchFromRss(String sourceName, String rssUrl, NewsArticle.Category category, Set<String> userSymbols) {
        if (rssUrl == null || rssUrl.isBlank() || rssUrl.startsWith("${")) {
            log.debug("Skipping source '{}': URL not configured ({})", sourceName, rssUrl);
            return 0;
        }
        int saved = 0;
        try {
            URL url = new URL(rssUrl);
            SyndFeedInput input = new SyndFeedInput();
            input.setAllowDoctypes(true);   // ET and some other Indian feeds include DOCTYPE declarations
            SyndFeed feed = input.build(new XmlReader(url));

            int count = 0;
            for (SyndEntry entry : feed.getEntries()) {
                if (count >= newsProperties.getMaxArticlesPerSource()) break;

                String articleUrl = entry.getLink();
                LocalDate pubDate = entry.getPublishedDate() != null
                        ? entry.getPublishedDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
                        : LocalDate.now();

                if (newsArticleRepository.existsByUrlAndPublishedDate(articleUrl, pubDate)) {
                    count++;
                    continue;
                }

                String title = entry.getTitle() != null ? entry.getTitle() : "";
                String summary = entry.getDescription() != null ? entry.getDescription().getValue() : "";
                // Strip HTML from summary
                summary = Jsoup.parse(summary).text();

                NewsArticle article = buildArticle(sourceName, title, summary, articleUrl,
                        pubDate, entry.getPublishedDate() != null
                                ? entry.getPublishedDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime()
                                : LocalDateTime.now(),
                        category, userSymbols);

                NewsArticle saved_article = newsArticleRepository.save(article);
                // Generate embedding asynchronously — does not block the fetch cycle
                embeddingService.generateAndStore(saved_article.getId(),
                        saved_article.getTitle(), saved_article.getSummary());
                saved++;
                count++;
            }
        } catch (Exception e) {
            log.warn("Failed to fetch RSS from {} ({}): {}", sourceName, rssUrl, e.getMessage());
            // Fallback: try Jsoup HTML scrape
            saved += fallbackHtmlScrape(sourceName, rssUrl, category, userSymbols);
        }
        return saved;
    }

    private int fallbackHtmlScrape(String sourceName, String siteUrl, NewsArticle.Category category, Set<String> userSymbols) {
        try {
            org.jsoup.nodes.Document doc = Jsoup.connect(siteUrl)
                    .timeout(newsProperties.getFetchTimeoutMs())
                    .userAgent("Mozilla/5.0 (compatible; PersonalCFO/1.0)")
                    .get();

            int saved = 0;
            // Grab all article headlines
            for (org.jsoup.nodes.Element el : doc.select("h2 a, h3 a, .article-title a").stream().limit(10).toList()) {
                String title = el.text().trim();
                String url = el.absUrl("href");

                if (title.isBlank() || url.isBlank()) continue;
                if (newsArticleRepository.existsByUrlAndPublishedDate(url, LocalDate.now())) continue;

                NewsArticle article = buildArticle(sourceName, title, "", url,
                        LocalDate.now(), LocalDateTime.now(), category, userSymbols);
                newsArticleRepository.save(article);
                saved++;
            }
            return saved;
        } catch (Exception e) {
            log.debug("HTML fallback also failed for {}: {}", sourceName, e.getMessage());
            return 0;
        }
    }

    // ── Relevance Scoring ─────────────────────────────────────────────────────

    private NewsArticle buildArticle(String source, String title, String summary,
                                     String url, LocalDate pubDate, LocalDateTime pubAt,
                                     NewsArticle.Category defaultCategory,
                                     Set<String> userSymbols) {

        // Run Tier 1 + Tier 2 pipeline
        NewsClassificationPipeline.NewsClassification c =
                classificationPipeline.classify(title, summary);

        // Keep base score generic — personalization happens at query-time in
        // PersonalizedRelevanceScorer. Only add held symbols to relatedSymbols so
        // Pillar 1 (portfolio-weight boost) can find them. (Phase 8: override removed)
        int relevanceScore = c.relevanceScore();
        NewsArticle.RelevanceLevel level = c.relevanceLevel();
        List<String> finalSymbols = new ArrayList<>(c.symbols());

        for (String userSym : userSymbols) {
            if (symbolExtractorService.mentionsSymbol(title + " " + summary, userSym)) {
                if (!finalSymbols.contains(userSym)) finalSymbols.add(userSym);
            }
        }

        return NewsArticle.builder()
                .source(source)
                .title(title)
                .summary(summary.length() > 1000 ? summary.substring(0, 1000) : summary)
                .url(url)
                .publishedDate(pubDate)
                .publishedAt(pubAt)
                .sentiment(c.sentiment())
                .category(c.category())
                .impactType(c.impactType())
                .impactHorizon(c.impactHorizon())
                .sectorImpact(c.sectorImpact())
                .relevanceScore(Math.min(100, relevanceScore))
                .relevanceLevel(level)
                .actionabilityScore(c.actionabilityScore())
                .relatedSymbols(String.join(",", finalSymbols))
                .relatedSectors(String.join(",", c.sectors()))
                .tier1Confidence(c.confidence())
                .llmReviewed(false)
                .build();
    }

    private Set<String> getUserHoldingSymbols() {
        // Get recently traded symbols from unified transaction ledger
        return transactionRepository.findRecentTransactions(defaultUserId, LocalDate.now().minusDays(365))
                .stream()
                .filter(t -> t.getSymbol() != null)
                .map(t -> t.getSymbol().toUpperCase())
                .collect(Collectors.toSet());
    }
}
