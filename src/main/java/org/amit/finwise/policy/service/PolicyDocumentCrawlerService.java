package org.amit.finwise.policy.service;

import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.document.service.PdfTextExtractionService;
import org.amit.finwise.policy.config.PolicySourceProperties;
import org.amit.finwise.policy.model.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class PolicyDocumentCrawlerService {

    private static final Set<String> PDF_KEYWORDS = Set.of(
            "pdf", "circular", "notification", "master direction", "master circular",
            "scheme", "guideline", "press release", "monetary policy", "resolution");

    private static final List<String> HIGH_IMPACT_KEYWORDS = List.of(
            "monetary", "policy", "yield", "repo", "interest rate", "inflation", 
            "bond", "liquidity", "money market", "price", "capital gain", "tax");

    private static final Pattern RBI_REFERENCE_PATTERN =
            Pattern.compile("\\bRBI/\\d{4}-\\d{2}/\\d+\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern RBI_DEPARTMENT_REFERENCE_PATTERN =
            Pattern.compile("\\b[A-Z]{2,8}(?:\\.[A-Z]{2,8}){1,6}\\.[A-Z0-9./-]*\\d{4}-\\d{2}\\b");
    private static final Pattern SEBI_REFERENCE_PATTERN =
            Pattern.compile("\\bSEBI/[A-Z0-9/_-]+\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PIB_RELEASE_ID_PATTERN =
            Pattern.compile("(?:press\\s*release\\s*id|pib\\s*release\\s*id|prid|relid|releaseid)\\s*[:=\\-]?\\s*(\\d+)",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern PIB_RELEASE_ID_URL_PATTERN =
            Pattern.compile("[?&](?:prid|id|relid|releaseid)=(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern GENERAL_REFERENCE_PATTERN =
            Pattern.compile("\\b(?:circular|notification|guideline|order|faq|scheme|master direction|master circular)"
                    + "\\s*(?:no\\.?|number)?\\s*[:\\-]?\\s*([A-Z0-9./()_-]{4,})", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE_TEXT_PATTERN =
            Pattern.compile("(?:dated|date)\\s*[:\\-]?\\s*(\\d{4}-\\d{2}-\\d{2}|\\d{1,2}[\\-/ ]"
                    + "[A-Za-z]{3,9}[\\-/ ]\\d{4})", Pattern.CASE_INSENSITIVE);

    private static final DateTimeFormatter[] DATE_FORMATTERS = new DateTimeFormatter[] {
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ENGLISH)
    };

    private final PolicySourceProperties properties;
    private final PdfTextExtractionService pdfTextExtractionService;
    private final PolicyIntelligenceService policyIntelligenceService;

    @Transactional
    public SyncResult syncAll() {
        int documents = 0;
        List<String> errors = new ArrayList<>();
        for (String sourceKey : properties.getSources().keySet()) {
            try {
                SyncResult result = syncSource(sourceKey);
                documents += result.documentsIngested();
                errors.addAll(result.errors());
            } catch (Exception e) {
                errors.add(sourceKey + ": " + e.getMessage());
                log.warn("[PolicyCrawler] Source {} failed: {}", sourceKey, e.getMessage());
            }
        }
        return new SyncResult("all", documents, errors);
    }

    @Transactional
    public SyncResult syncSource(String sourceKey) {
        PolicySourceProperties.SourceConfig config = properties.getSources().get(sourceKey);
        if (config == null) {
            throw new IllegalArgumentException("Unknown policy source: " + sourceKey);
        }
        if (!config.isEnabled()) {
            return new SyncResult(sourceKey, 0, List.of());
        }

        List<PolicyEntryDraft> entries = fetchPolicyEntries(config);
        int ingested = 0;
        List<String> errors = new ArrayList<>();
        int processed = 0;

        for (PolicyEntryDraft entry : entries) {
            if (processed >= properties.getMaxArticlesPerSource()) {
                break;
            }

            // High-impact Keyword Filter
            if (!isHighImpact(entry.title())) {
                log.debug("[PolicyCrawler] Skipping low-impact entry: {}", entry.title());
                processed++;
                continue;
            }

            try {
                PolicyFetchPayload payload = fetchEntryFromDraft(config, entry);
                if (payload == null || payload.normalizedText() == null || payload.normalizedText().length() < 300) {
                    processed++;
                    continue;
                }

                policyIntelligenceService.ingestDocument(new PolicyIntelligenceService.PolicyIngestionRequest(
                        payload.documentKey(),
                        payload.authority(),
                        payload.documentType(),
                        payload.bindingLevel(),
                        payload.policyArea(),
                        PolicyDocumentStatus.ACTIVE,
                        payload.title(),
                        payload.sourceUrl(),
                        payload.sourceReference(),
                        payload.externalDocumentId(),
                        payload.publishedDate(),
                        payload.effectiveFrom(),
                        null,
                        payload.issuedAt(),
                        null,
                        null,
                        payload.summary(),
                        payload.tags(),
                        payload.affectedSectors(),
                        payload.metadataJson(),
                        payload.normalizedText(),
                        payload.impacts()
                ));
                ingested++;
            } catch (Exception e) {
                errors.add(entry.link() + ": " + e.getMessage());
                log.warn("[PolicyCrawler] Failed entry {}: {}", entry.link(), e.getMessage());
            }
            processed++;
        }

        return new SyncResult(sourceKey, ingested, errors);
    }

    private boolean isHighImpact(String title) {
        if (title == null) return false;
        String lower = title.toLowerCase(Locale.ENGLISH);
        return HIGH_IMPACT_KEYWORDS.stream().anyMatch(lower::contains);
    }

    private List<PolicyEntryDraft> fetchPolicyEntries(PolicySourceProperties.SourceConfig config) {
        // Fallback to HTML scraping if the URL is an ASPX page (common for RBI)
        if (config.getFeedUrl().contains(".aspx")) {
            return scrapeHtmlEntries(config.getFeedUrl());
        }
        
        // Otherwise try RSS but with a fallback to HTML if XML parsing fails
        try {
            return fetchFeedEntries(config.getFeedUrl()).stream()
                    .map(e -> new PolicyEntryDraft(e.getTitle(), e.getLink(), e.getPublishedDate()))
                    .toList();
        } catch (Exception e) {
            log.warn("[PolicyCrawler] RSS failed for {}, falling back to HTML scrape: {}", config.getFeedUrl(), e.getMessage());
            return scrapeHtmlEntries(config.getFeedUrl());
        }
    }

    private List<PolicyEntryDraft> scrapeHtmlEntries(String url) {
        try {
            Document doc = Jsoup.connect(url)
                    .userAgent(properties.getUserAgent())
                    .timeout(properties.getFetchTimeoutMs())
                    .get();
            
            List<PolicyEntryDraft> entries = new ArrayList<>();
            // Generic strategy: find all links in the main content area
            Elements links = doc.select("a[href]");
            for (Element link : links) {
                String title = link.text().trim();
                String href = link.absUrl("href");
                if (title.length() > 20 && (href.contains(".pdf") || href.contains("prid=") || href.contains("Scripts/BS_"))) {
                    entries.add(new PolicyEntryDraft(title, href, null));
                }
            }
            return entries;
        } catch (Exception e) {
            log.error("[PolicyCrawler] HTML scrape failed for {}: {}", url, e.getMessage());
            return List.of();
        }
    }

    private List<SyndEntry> fetchFeedEntries(String feedUrl) {
        if (feedUrl == null || feedUrl.isBlank()) {
            return List.of();
        }
        try {
            // Use Jsoup to fetch the content first to ensure we use our User-Agent
            String content = Jsoup.connect(feedUrl)
                    .userAgent(properties.getUserAgent())
                    .timeout(properties.getFetchTimeoutMs())
                    .execute()
                    .body();

            SyndFeedInput input = new SyndFeedInput();
            input.setAllowDoctypes(true);
            SyndFeed feed = input.build(new XmlReader(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))));
            return feed.getEntries();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to fetch/parse policy feed " + feedUrl, e);
        }
    }

    private PolicyFetchPayload fetchEntryFromDraft(PolicySourceProperties.SourceConfig config, PolicyEntryDraft draft) {
        PolicyAuthority authority = parseAuthority(config.getAuthority());
        String title = draft.title();
        String entryUrl = draft.link();
        LocalDateTime publishedAt = draft.publishedDate() != null
                ? draft.publishedDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime()
                : LocalDateTime.now();
        LocalDate publishedDate = publishedAt.toLocalDate();

        if (looksLikePdf(entryUrl)) {
            return fetchPdfPayload(config, title, entryUrl, entryUrl, publishedAt, publishedDate, null);
        }

        if (!properties.isHtmlExtractionEnabled()) {
            return null;
        }

        Document doc = fetchHtml(entryUrl);
        String pdfUrl = findPdfUrl(doc, entryUrl, config.getBaseUrl(), authority);
        if (pdfUrl != null && properties.isPdfExtractionEnabled()) {
            return fetchPdfPayload(config, titleFromDoc(doc, title, authority), pdfUrl, entryUrl, publishedAt, publishedDate, null);
        }

        String htmlText = extractHtmlText(doc, authority);
        return buildPayloadFromText(
                config,
                titleFromDoc(doc, title, authority),
                entryUrl,
                entryUrl,
                publishedAt,
                publishedDate,
                htmlText,
                null,
                inferEffectiveDate(doc, publishedDate),
                summaryFromHtml(doc, null, authority)
        );
    }

    private PolicyFetchPayload fetchPdfPayload(PolicySourceProperties.SourceConfig config,
                                               String fallbackTitle,
                                               String pdfUrl,
                                               String sourceUrl,
                                               LocalDateTime publishedAt,
                                               LocalDate publishedDate,
                                               SyndEntry entry) {
        byte[] bytes = downloadBytes(pdfUrl);
        persistArtifact(pdfUrl, bytes);
        String text = pdfTextExtractionService.extract(new ByteArrayInputStream(bytes), null);
        return buildPayloadFromText(
                config,
                fallbackTitle,
                sourceUrl,
                pdfUrl,
                publishedAt,
                publishedDate,
                normalizePolicyText(text),
                entry,
                publishedDate,
                entry != null ? summaryFromEntry(entry) : null
        );
    }

    private PolicyFetchPayload buildPayloadFromText(PolicySourceProperties.SourceConfig config,
                                                    String title,
                                                    String sourceUrl,
                                                    String artifactUrl,
                                                    LocalDateTime publishedAt,
                                                    LocalDate publishedDate,
                                                    String normalizedText,
                                                    SyndEntry entry,
                                                    LocalDate effectiveDate,
                                                    String summary) {
        if (normalizedText == null || normalizedText.isBlank()) {
            return null;
        }

        PolicyAuthority authority = parseAuthority(config.getAuthority());
        String canonicalSourceUrl = canonicalizeUrl(sourceUrl);
        String canonicalArtifactUrl = canonicalizeUrl(artifactUrl);
        PolicyDocumentType documentType = inferDocumentType(config.getDocumentType(), title, normalizedText, sourceUrl);
        PolicyBindingLevel bindingLevel = inferBindingLevel(config.getBindingLevel(), authority, documentType, title);
        PolicyArea policyArea = inferPolicyArea(config.getPolicyArea(), authority, title, normalizedText);
        List<String> tags = inferTags(authority, documentType, title, normalizedText);
        List<String> sectors = inferSectors(title, normalizedText);
        String sourceReference = buildCanonicalSourceReference(
                authority,
                title,
                normalizedText,
                canonicalSourceUrl,
                canonicalArtifactUrl
        );
        String externalDocumentId = buildExternalDocumentId(canonicalSourceUrl, canonicalArtifactUrl);
        List<PolicyIntelligenceService.PolicyImpactDraft> impacts =
                inferImpacts(authority, policyArea, title, normalizedText, effectiveDate);

        return new PolicyFetchPayload(
                buildDocumentKey(authority, sourceReference, externalDocumentId, canonicalSourceUrl),
                authority,
                documentType,
                bindingLevel,
                policyArea,
                title,
                canonicalSourceUrl,
                sourceReference,
                summary,
                publishedDate,
                effectiveDate,
                publishedAt,
                tags,
                sectors,
                buildMetadataJson(canonicalArtifactUrl, canonicalSourceUrl, sourceReference, title),
                normalizedText,
                externalDocumentId,
                impacts
        );
    }

    private Document fetchHtml(String url) {
        try {
            return Jsoup.connect(url)
                    .timeout(properties.getFetchTimeoutMs())
                    .userAgent(properties.getUserAgent())
                    .get();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to fetch html " + url, e);
        }
    }

    private byte[] downloadBytes(String url) {
        try {
            return Jsoup.connect(url)
                    .ignoreContentType(true)
                    .timeout(properties.getFetchTimeoutMs())
                    .userAgent(properties.getUserAgent())
                    .execute()
                    .bodyAsBytes();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to download artifact " + url, e);
        }
    }

    private void persistArtifact(String url, byte[] bytes) {
        try {
            Path root = Path.of(properties.getStorageDir());
            Files.createDirectories(root);
            String filename = url.substring(url.lastIndexOf('/') + 1).replaceAll("[^a-zA-Z0-9._-]", "_");
            if (filename.isBlank()) {
                filename = "artifact-" + System.nanoTime() + ".bin";
            }
            Files.write(root.resolve(filename), bytes);
        } catch (Exception e) {
            log.debug("[PolicyCrawler] Could not persist artifact {}: {}", url, e.getMessage());
        }
    }

    private String findPdfUrl(Document doc, String pageUrl, String baseUrl, PolicyAuthority authority) {
        Map<String, Integer> candidates = new LinkedHashMap<>();
        for (Element link : selectCandidateLinks(doc, authority)) {
            String href = link.absUrl("href");
            if (href == null || href.isBlank()) {
                href = resolveHref(baseUrl != null ? baseUrl : pageUrl, link.attr("href"));
            }
            if (!isPdfCandidateUrl(href)) {
                continue;
            }

            String canonicalHref = canonicalizeUrl(href);
            String text = (link.text() + " " + href).toLowerCase(Locale.ENGLISH);
            int score = looksLikePdf(href) ? 120 : 80;
            if (PDF_KEYWORDS.stream().anyMatch(text::contains)) {
                score += 25;
            }
            if (authority == PolicyAuthority.RBI && text.contains("master")) {
                score += 10;
            }
            if (authority == PolicyAuthority.SEBI && text.contains("circular")) {
                score += 10;
            }
            if (authority == PolicyAuthority.PIB && text.contains("release")) {
                score += 10;
            }
            candidates.merge(canonicalHref, score, Math::max);
        }

        return candidates.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private String resolveHref(String baseUrl, String href) {
        try {
            return URI.create(baseUrl).resolve(href).toString();
        } catch (Exception e) {
            return href;
        }
    }

    private boolean looksLikePdf(String url) {
        return url != null && url.toLowerCase(Locale.ENGLISH).contains(".pdf");
    }

    private boolean isPdfCandidateUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String lower = url.toLowerCase(Locale.ENGLISH);
        return lower.contains(".pdf") || lower.contains("/pdf") || lower.contains("downloadpdf");
    }

    private List<Element> selectCandidateLinks(Document doc, PolicyAuthority authority) {
        Element contentRoot = selectContentRoot(doc, authority);
        LinkedHashMap<String, Element> links = new LinkedHashMap<>();
        if (contentRoot != null) {
            for (Element link : contentRoot.select("a[href]")) {
                links.putIfAbsent(link.outerHtml(), link);
            }
        }
        for (Element link : doc.select("a[href]")) {
            links.putIfAbsent(link.outerHtml(), link);
        }
        return new ArrayList<>(links.values());
    }

    private String titleFromDoc(Document doc, String fallback, PolicyAuthority authority) {
        String title = doc.select("meta[property=og:title]").attr("content");
        if (title == null || title.isBlank()) {
            Element heading = firstNonNull(
                    selectFirst(doc, selectorsForHeadings(authority)),
                    doc.selectFirst("h1"),
                    doc.selectFirst("h2")
            );
            title = heading != null ? heading.text() : null;
        }
        if (title == null || title.isBlank()) {
            title = doc.title();
        }
        title = title == null || title.isBlank() ? fallback : title.trim();
        return cleanDocumentTitle(title);
    }

    private String summaryFromHtml(Document doc, SyndEntry entry, PolicyAuthority authority) {
        String summary = doc.select("meta[name=description]").attr("content");
        if (summary == null || summary.isBlank()) {
            Element contentRoot = selectContentRoot(doc, authority);
            Element paragraph = contentRoot != null ? contentRoot.selectFirst("p") : null;
            summary = paragraph != null ? paragraph.text() : null;
        }
        if (summary == null || summary.isBlank()) {
            summary = entry != null ? summaryFromEntry(entry) : null;
        }
        return summary == null ? null : abbreviate(normalizePolicyText(summary), 320);
    }

    private String summaryFromEntry(SyndEntry entry) {
        return entry.getDescription() != null
                ? Jsoup.parse(entry.getDescription().getValue()).text()
                : null;
    }

    private String extractHtmlText(Document doc, PolicyAuthority authority) {
        Element article = selectContentRoot(doc, authority);
        String selectedText = extractCleanText(article);
        if (selectedText != null && selectedText.length() >= 400) {
            return normalizePolicyText(selectedText);
        }

        String bodyText = extractCleanText(doc.body());
        return normalizePolicyText(bodyText != null ? bodyText : doc.text());
    }

    private Element selectContentRoot(Document doc, PolicyAuthority authority) {
        Element sourceSpecific = selectFirst(doc, selectorsForContent(authority));
        if (sourceSpecific != null) {
            return sourceSpecific;
        }
        return firstNonNull(
                doc.selectFirst("article"),
                doc.selectFirst("main"),
                doc.selectFirst(".press-release"),
                doc.selectFirst(".field-item"),
                doc.selectFirst(".content"),
                doc.body()
        );
    }

    private Element selectFirst(Document doc, List<String> selectors) {
        for (String selector : selectors) {
            Element found = doc.selectFirst(selector);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private List<String> selectorsForContent(PolicyAuthority authority) {
        return switch (authority) {
            case RBI -> List.of(
                    "#skip_content",
                    ".content_area",
                    ".content",
                    "#content",
                    ".middle-content",
                    ".data-container",
                    "main"
            );
            case SEBI -> List.of(
                    "#maincontent",
                    ".innner-page-content",
                    ".inner-page-content",
                    ".content-area",
                    ".common-content",
                    ".field-item",
                    "main"
            );
            case PIB -> List.of(
                    ".innner-page-main-aboutus-content",
                    ".field--name-body",
                    ".field-item",
                    ".ReleaseContent",
                    ".content-area",
                    "main"
            );
            default -> List.of("article", "main", ".content");
        };
    }

    private List<String> selectorsForHeadings(PolicyAuthority authority) {
        return switch (authority) {
            case RBI -> List.of("#skip_content h1", ".content_area h1", ".content h1", "main h1");
            case SEBI -> List.of("#maincontent h1", ".innner-page-content h1", ".inner-page-content h1", "main h1");
            case PIB -> List.of(".innner-page-main-aboutus-content h1", ".ReleaseContent h1", "main h1");
            default -> List.of("article h1", "main h1");
        };
    }

    private String extractCleanText(Element root) {
        if (root == null) {
            return null;
        }
        Element clone = root.clone();
        clone.select("script,style,noscript,nav,footer,header,form,button,svg,aside,"
                + ".breadcrumb,.breadcrumbs,.share,.social-share,.print,.download,"
                + ".related-links,.other_links,.quick-links").remove();
        return clone.text();
    }

    @SafeVarargs
    private <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String normalizePolicyText(String rawText) {
        if (rawText == null) {
            return null;
        }
        return rawText
                .replace('\u00A0', ' ')
                .replaceAll("(\\r\\n|\\r)", "\n")
                .replaceAll("(?i)download pdf", " ")
                .replaceAll("(?i)click here to view", " ")
                .replaceAll("Page \\d+ of \\d+", " ")
                .replaceAll("\\bReserve Bank of India\\b", "Reserve Bank of India")
                .replaceAll("[ \\t]{2,}", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private PolicyAuthority parseAuthority(String raw) {
        if (raw == null || raw.isBlank()) {
            return PolicyAuthority.OTHER;
        }
        try {
            return PolicyAuthority.valueOf(raw.trim().toUpperCase(Locale.ENGLISH));
        } catch (Exception e) {
            return PolicyAuthority.OTHER;
        }
    }

    private PolicyDocumentType inferDocumentType(String configured, String title, String text, String url) {
        if (configured != null && !configured.isBlank()) {
            try {
                return PolicyDocumentType.valueOf(configured.trim().toUpperCase(Locale.ENGLISH));
            } catch (Exception ignored) {
            }
        }

        String haystack = (title + " " + text + " " + url).toLowerCase(Locale.ENGLISH);
        if (haystack.contains("monetary policy") || haystack.contains("mpc")) return PolicyDocumentType.MONETARY_POLICY;
        if (haystack.contains("master direction")) return PolicyDocumentType.MASTER_DIRECTION;
        if (haystack.contains("consultation paper")) return PolicyDocumentType.CONSULTATION_PAPER;
        if (haystack.contains("circular")) return PolicyDocumentType.CIRCULAR;
        if (haystack.contains("notification")) return PolicyDocumentType.NOTIFICATION;
        if (haystack.contains("scheme")) return PolicyDocumentType.SCHEME;
        if (haystack.contains("budget")) return PolicyDocumentType.BUDGET;
        if (haystack.contains("press release")) return PolicyDocumentType.PRESS_RELEASE;
        if (haystack.contains("announcement")) return PolicyDocumentType.ANNOUNCEMENT;
        if (haystack.contains("faq")) return PolicyDocumentType.FAQ;
        if (haystack.contains(" act ")) return PolicyDocumentType.ACT;
        if (haystack.contains(" rules ") || haystack.contains("rule,")) return PolicyDocumentType.RULE;
        if (haystack.contains("order")) return PolicyDocumentType.ORDER;
        if (haystack.contains("guideline")) return PolicyDocumentType.GUIDELINE;
        return PolicyDocumentType.OTHER;
    }

    private PolicyBindingLevel inferBindingLevel(String configured,
                                                 PolicyAuthority authority,
                                                 PolicyDocumentType documentType,
                                                 String title) {
        if (configured != null && !configured.isBlank()) {
            try {
                return PolicyBindingLevel.valueOf(configured.trim().toUpperCase(Locale.ENGLISH));
            } catch (Exception ignored) {
            }
        }
        if (authority == PolicyAuthority.NASSCOM) return PolicyBindingLevel.INDUSTRY_BODY;
        if (documentType == PolicyDocumentType.PRESS_RELEASE || documentType == PolicyDocumentType.REPORT) {
            return PolicyBindingLevel.INFORMATIONAL;
        }
        if (title.toLowerCase(Locale.ENGLISH).contains("consultation")) {
            return PolicyBindingLevel.ADVISORY;
        }
        return PolicyBindingLevel.BINDING;
    }

    private PolicyArea inferPolicyArea(String configured, PolicyAuthority authority, String title, String text) {
        if (configured != null && !configured.isBlank()) {
            try {
                return PolicyArea.valueOf(configured.trim().toUpperCase(Locale.ENGLISH));
            } catch (Exception ignored) {
            }
        }

        String haystack = (title + " " + text).toLowerCase(Locale.ENGLISH);
        if (authority == PolicyAuthority.RBI || haystack.contains("repo rate") || haystack.contains("mpc")) {
            return PolicyArea.MONETARY_POLICY;
        }
        if (authority == PolicyAuthority.SEBI) return PolicyArea.SECURITIES_REGULATION;
        if (authority == PolicyAuthority.CBDT || authority == PolicyAuthority.INCOME_TAX_DEPARTMENT
                || haystack.contains("tax") || haystack.contains("capital gain")) {
            return PolicyArea.TAXATION;
        }
        if (containsAny(haystack, "scheme", "yojana", "subsidy", "incentive", "benefit", "mission", "programme", "program")) {
            return PolicyArea.GOVERNMENT_SCHEME;
        }
        if (authority == PolicyAuthority.PIB || authority == PolicyAuthority.MINISTRY_OF_FINANCE) {
            return PolicyArea.MACRO_ECONOMY;
        }
        return PolicyArea.OTHER;
    }

    private List<String> inferTags(PolicyAuthority authority,
                                   PolicyDocumentType documentType,
                                   String title,
                                   String text) {
        Set<String> tags = new LinkedHashSet<>();
        tags.add(authority.name());
        tags.add(documentType.name());
        String haystack = (title + " " + text).toLowerCase(Locale.ENGLISH);
        if (haystack.contains("repo")) tags.add("repo-rate");
        if (haystack.contains("capital gain")) tags.add("capital-gains");
        if (haystack.contains("dividend")) tags.add("dividend-tax");
        if (haystack.contains("ltcg")) tags.add("ltcg");
        if (haystack.contains("stcg")) tags.add("stcg");
        if (haystack.contains("set off") || haystack.contains("carry forward")) tags.add("loss-setoff");
        if (haystack.contains("mutual fund")) tags.add("mutual-funds");
        if (haystack.contains("equity")) tags.add("equity");
        if (haystack.contains("retail investor")) tags.add("retail-investor");
        if (haystack.contains("scheme")) tags.add("scheme");
        if (containsAny(haystack, "subsidy", "incentive", "pli", "production linked incentive")) tags.add("industrial-policy");
        if (containsAny(haystack, "affordable housing", "housing scheme")) tags.add("housing");
        if (containsAny(haystack, "msme", "micro small and medium")) tags.add("msme");
        return new ArrayList<>(tags);
    }

    private List<String> inferSectors(String title, String text) {
        String haystack = (title + " " + text).toLowerCase(Locale.ENGLISH);
        Set<String> sectors = new LinkedHashSet<>();
        if (haystack.contains("bank") || haystack.contains("nbfc")) sectors.add("Banking");
        if (haystack.contains("realty") || haystack.contains("housing")) sectors.add("Realty");
        if (haystack.contains("mutual fund") || haystack.contains("capital market")) sectors.add("Capital Markets");
        if (haystack.contains("insurance")) sectors.add("Insurance");
        if (haystack.contains("infrastructure")) sectors.add("Infrastructure");
        if (containsAny(haystack, "manufacturing", "production linked incentive", "pli")) sectors.add("Manufacturing");
        if (containsAny(haystack, "renewable", "solar", "wind", "energy transition")) sectors.add("Energy");
        return new ArrayList<>(sectors);
    }

    private List<PolicyIntelligenceService.PolicyImpactDraft> inferImpacts(PolicyAuthority authority,
                                                                           PolicyArea policyArea,
                                                                           String title,
                                                                           String text,
                                                                           LocalDate effectiveDate) {
        String haystack = (title + " " + text).toLowerCase(Locale.ENGLISH);
        Map<String, PolicyIntelligenceService.PolicyImpactDraft> impacts = new LinkedHashMap<>();

        if (authority == PolicyAuthority.RBI && (haystack.contains("repo rate") || haystack.contains("monetary policy"))) {
            addImpact(impacts, "sector:banking", new PolicyIntelligenceService.PolicyImpactDraft(
                    policyArea,
                    PolicySubjectType.SECTOR,
                    "Banking",
                    "Banking",
                    haystack.contains("cut") ? PolicyImpactDirection.POSITIVE : PolicyImpactDirection.MIXED,
                    PolicyImpactHorizon.SHORT_TERM,
                    effectiveDate,
                    null,
                    0.75,
                    "Monetary policy action materially affects rate-sensitive financial businesses.",
                    "Derived automatically from RBI monetary policy language.",
                    List.of("auto-derived", "rbi")
            ));
            addImpact(impacts, "sector:realty", new PolicyIntelligenceService.PolicyImpactDraft(
                    policyArea,
                    PolicySubjectType.SECTOR,
                    "Realty",
                    "Realty",
                    haystack.contains("cut") ? PolicyImpactDirection.POSITIVE : PolicyImpactDirection.NEGATIVE,
                    PolicyImpactHorizon.SHORT_TERM,
                    effectiveDate,
                    null,
                    0.72,
                    "Interest-rate changes flow through housing demand and cost of borrowing.",
                    "Derived automatically from RBI rate-sensitive sector mapping.",
                    List.of("auto-derived", "rbi")
            ));
        }

        if (authority == PolicyAuthority.SEBI) {
            addImpact(impacts, "sector:capital-markets", new PolicyIntelligenceService.PolicyImpactDraft(
                    policyArea,
                    PolicySubjectType.SECTOR,
                    "Capital Markets",
                    "Capital Markets",
                    PolicyImpactDirection.MIXED,
                    PolicyImpactHorizon.MEDIUM_TERM,
                    effectiveDate,
                    null,
                    0.65,
                    "SEBI circulars often alter compliance, disclosures, investor protection, and market-process rules.",
                    "Generic auto-derived impact for SEBI-origin retail-market documents.",
                    List.of("auto-derived", "sebi")
            ));
        }

        if (policyArea == PolicyArea.TAXATION || haystack.contains("capital gain") || haystack.contains("stcg") || haystack.contains("ltcg")) {
            addImpact(impacts, "tax:capital-gains", new PolicyIntelligenceService.PolicyImpactDraft(
                    PolicyArea.TAXATION,
                    PolicySubjectType.TAX_TOPIC,
                    "capital-gains",
                    "Capital Gains",
                    PolicyImpactDirection.MIXED,
                    PolicyImpactHorizon.STRUCTURAL,
                    effectiveDate,
                    null,
                    0.70,
                    "The document may affect after-tax returns, harvesting decisions, and holding-period strategy.",
                    "Auto-derived tax impact for capital gains related policy text.",
                    List.of("auto-derived", "tax")
            ));
        }

        if (policyArea == PolicyArea.TAXATION || containsAny(haystack, "dividend distribution", "dividend income", "dividend tax", "tds on dividend", "dividend")) {
            addImpact(impacts, "tax:dividend-tax", new PolicyIntelligenceService.PolicyImpactDraft(
                    PolicyArea.TAXATION,
                    PolicySubjectType.TAX_TOPIC,
                    "dividend-tax",
                    "Dividend Taxation",
                    PolicyImpactDirection.MIXED,
                    PolicyImpactHorizon.STRUCTURAL,
                    effectiveDate,
                    null,
                    0.68,
                    "Dividend taxation changes alter post-tax cash yields and portfolio income expectations.",
                    "Auto-derived from dividend-related tax language.",
                    List.of("auto-derived", "tax", "dividend")
            ));
        }

        if (policyArea == PolicyArea.TAXATION || containsAny(haystack, "set off", "set-off", "carry forward", "carry-forward", "loss adjustment")) {
            addImpact(impacts, "tax:loss-setoff", new PolicyIntelligenceService.PolicyImpactDraft(
                    PolicyArea.TAXATION,
                    PolicySubjectType.TAX_TOPIC,
                    "loss-setoff",
                    "Loss Set-Off and Carry Forward",
                    PolicyImpactDirection.MIXED,
                    PolicyImpactHorizon.STRUCTURAL,
                    effectiveDate,
                    null,
                    0.72,
                    "Changes to loss set-off rules affect tax-loss harvesting, net-realisation timing, and rebalancing decisions.",
                    "Auto-derived from set-off and carry-forward language.",
                    List.of("auto-derived", "tax", "loss-setoff")
            ));
        }

        if (policyArea == PolicyArea.TAXATION || containsAny(haystack, "securities transaction tax", "stt")) {
            addImpact(impacts, "tax:stt", new PolicyIntelligenceService.PolicyImpactDraft(
                    PolicyArea.TAXATION,
                    PolicySubjectType.TAX_TOPIC,
                    "stt",
                    "Securities Transaction Tax",
                    PolicyImpactDirection.MIXED,
                    PolicyImpactHorizon.STRUCTURAL,
                    effectiveDate,
                    null,
                    0.66,
                    "STT changes alter effective trading costs and can shift the attractiveness of active turnover.",
                    "Auto-derived from STT references.",
                    List.of("auto-derived", "tax", "stt")
            ));
        }

        if (containsAny(haystack, "tax harvesting", "harvesting losses", "harvest losses", "book profits")) {
            addImpact(impacts, "tax:tax-harvesting", new PolicyIntelligenceService.PolicyImpactDraft(
                    PolicyArea.TAXATION,
                    PolicySubjectType.TAX_TOPIC,
                    "tax-harvesting",
                    "Tax Harvesting",
                    PolicyImpactDirection.MIXED,
                    PolicyImpactHorizon.MEDIUM_TERM,
                    effectiveDate,
                    null,
                    0.60,
                    "The document may change the optimal timing of gain realisation and offset strategies.",
                    "Auto-derived from tax-harvesting related wording.",
                    List.of("auto-derived", "tax", "tax-harvesting")
            ));
        }

        if (policyArea == PolicyArea.GOVERNMENT_SCHEME || containsAny(haystack, "scheme", "yojana", "subsidy", "incentive", "benefit", "mission", "programme", "program")) {
            addImpact(impacts, "theme:government-schemes", new PolicyIntelligenceService.PolicyImpactDraft(
                    PolicyArea.GOVERNMENT_SCHEME,
                    PolicySubjectType.THEME,
                    "government-schemes",
                    "Government Schemes",
                    PolicyImpactDirection.POSITIVE,
                    PolicyImpactHorizon.STRUCTURAL,
                    effectiveDate,
                    null,
                    0.62,
                    "Scheme announcements can improve demand visibility, capital allocation, or beneficiary cash flows over time.",
                    "Auto-derived from scheme and incentive language.",
                    List.of("auto-derived", "scheme")
            ));
        }

        if (containsAny(haystack, "infrastructure", "roads", "railway", "metro", "logistics", "ports")) {
            addImpact(impacts, "sector:infrastructure", new PolicyIntelligenceService.PolicyImpactDraft(
                    PolicyArea.GOVERNMENT_SCHEME,
                    PolicySubjectType.SECTOR,
                    "Infrastructure",
                    "Infrastructure",
                    PolicyImpactDirection.POSITIVE,
                    PolicyImpactHorizon.STRUCTURAL,
                    effectiveDate,
                    null,
                    0.74,
                    "Infrastructure-oriented schemes can support order books, execution pipelines, and long-duration capex themes.",
                    "Auto-derived from infrastructure policy language.",
                    List.of("auto-derived", "scheme", "infrastructure")
            ));
        }

        if (containsAny(haystack, "manufacturing", "production linked incentive", "pli", "factory", "local manufacturing")) {
            addImpact(impacts, "sector:manufacturing", new PolicyIntelligenceService.PolicyImpactDraft(
                    PolicyArea.GOVERNMENT_SCHEME,
                    PolicySubjectType.SECTOR,
                    "Manufacturing",
                    "Manufacturing",
                    PolicyImpactDirection.POSITIVE,
                    PolicyImpactHorizon.STRUCTURAL,
                    effectiveDate,
                    null,
                    0.76,
                    "Manufacturing incentives can improve domestic capex, import substitution, and medium-term earnings visibility.",
                    "Auto-derived from manufacturing and PLI language.",
                    List.of("auto-derived", "scheme", "manufacturing")
            ));
        }

        if (containsAny(haystack, "housing", "affordable housing", "home loan subsidy", "pmay")) {
            addImpact(impacts, "sector:realty-scheme", new PolicyIntelligenceService.PolicyImpactDraft(
                    PolicyArea.GOVERNMENT_SCHEME,
                    PolicySubjectType.SECTOR,
                    "Realty",
                    "Realty",
                    PolicyImpactDirection.POSITIVE,
                    PolicyImpactHorizon.MEDIUM_TERM,
                    effectiveDate,
                    null,
                    0.71,
                    "Housing-linked schemes can improve affordability, financing access, and demand for residential real estate.",
                    "Auto-derived from housing-scheme language.",
                    List.of("auto-derived", "scheme", "housing")
            ));
        }

        if (containsAny(haystack, "msme", "micro small and medium enterprises", "small business")) {
            addImpact(impacts, "theme:msme-support", new PolicyIntelligenceService.PolicyImpactDraft(
                    PolicyArea.GOVERNMENT_SCHEME,
                    PolicySubjectType.THEME,
                    "msme-support",
                    "MSME Support",
                    PolicyImpactDirection.POSITIVE,
                    PolicyImpactHorizon.MEDIUM_TERM,
                    effectiveDate,
                    null,
                    0.70,
                    "MSME-focused measures can support credit demand, working-capital resilience, and domestic supply chains.",
                    "Auto-derived from MSME support language.",
                    List.of("auto-derived", "scheme", "msme")
            ));
        }

        if (containsAny(haystack, "renewable", "solar", "wind", "green energy", "electric vehicle", "ev")) {
            addImpact(impacts, "theme:energy-transition", new PolicyIntelligenceService.PolicyImpactDraft(
                    PolicyArea.GOVERNMENT_SCHEME,
                    PolicySubjectType.THEME,
                    "energy-transition",
                    "Energy Transition",
                    PolicyImpactDirection.POSITIVE,
                    PolicyImpactHorizon.STRUCTURAL,
                    effectiveDate,
                    null,
                    0.69,
                    "Energy-transition schemes can improve long-term demand for clean-energy and allied manufacturing ecosystems.",
                    "Auto-derived from renewable and EV policy language.",
                    List.of("auto-derived", "scheme", "energy-transition")
            ));
        }

        return new ArrayList<>(impacts.values());
    }

    private LocalDate inferEffectiveDate(Document doc, LocalDate fallback) {
        if (doc == null) {
            return fallback;
        }
        for (String selector : List.of(
                "meta[property=article:published_time]",
                "meta[name=publish-date]",
                "meta[name=Date]",
                "meta[name=dc.date]",
                "meta[name=DC.date.issued]",
                "meta[name=dc.date.issued]"
        )) {
            String value = doc.select(selector).attr("content");
            LocalDate parsed = parseDate(value);
            if (parsed != null) {
                return parsed;
            }
        }
        LocalDate textDate = parseDateFromText(doc.text());
        if (textDate != null) {
            return textDate;
        }
        return fallback;
    }

    private LocalDate parseDateFromText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = DATE_TEXT_PATTERN.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        return parseDate(matcher.group(1));
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.substring(0, 10));
        } catch (Exception ignored) {
        }
        for (DateTimeFormatter formatter : DATE_FORMATTERS) {
            try {
                return LocalDate.parse(value.trim(), formatter);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private String buildDocumentKey(PolicyAuthority authority,
                                    String sourceReference,
                                    String externalDocumentId,
                                    String sourceUrl) {
        String stableIdentifier = firstNonBlank(sourceReference, externalDocumentId, sourceUrl);
        return authority.name().toLowerCase(Locale.ENGLISH) + ":"
                + UUID.nameUUIDFromBytes(stableIdentifier.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String buildCanonicalSourceReference(PolicyAuthority authority,
                                                 String title,
                                                 String normalizedText,
                                                 String sourceUrl,
                                                 String artifactUrl) {
        String corpus = String.join("\n",
                firstNonBlank(title, ""),
                firstNonBlank(normalizedText, ""),
                firstNonBlank(sourceUrl, ""),
                firstNonBlank(artifactUrl, "")
        );

        String authoritySpecific = switch (authority) {
            case RBI -> firstNonBlank(
                    extractPatternMatch(RBI_REFERENCE_PATTERN, corpus),
                    extractPatternMatch(RBI_DEPARTMENT_REFERENCE_PATTERN, corpus)
            );
            case SEBI -> extractPatternMatch(SEBI_REFERENCE_PATTERN, corpus);
            case PIB -> {
                String releaseId = firstNonBlank(
                        extractPatternMatch(PIB_RELEASE_ID_PATTERN, corpus, 1),
                        extractPatternMatch(PIB_RELEASE_ID_URL_PATTERN, sourceUrl, 1),
                        extractPatternMatch(PIB_RELEASE_ID_URL_PATTERN, artifactUrl, 1)
                );
                yield releaseId != null ? "PIB-RELEASE-ID:" + releaseId : null;
            }
            default -> null;
        };

        String genericReference = extractPatternMatch(GENERAL_REFERENCE_PATTERN, corpus, 1);
        return normalizeSourceReference(firstNonBlank(authoritySpecific, genericReference, artifactUrl, sourceUrl));
    }

    private String buildExternalDocumentId(String sourceUrl, String artifactUrl) {
        return firstNonBlank(artifactUrl, sourceUrl);
    }

    private String normalizeSourceReference(String reference) {
        if (reference == null || reference.isBlank()) {
            return null;
        }
        if (reference.startsWith("http://") || reference.startsWith("https://")
                || reference.startsWith("HTTP://") || reference.startsWith("HTTPS://")) {
            return canonicalizeUrl(reference);
        }
        return reference.trim()
                .replaceAll("\\s+", " ")
                .replaceAll("\\s*/\\s*", "/")
                .replaceAll("\\s*:\\s*", ":")
                .toUpperCase(Locale.ENGLISH);
    }

    private String extractPatternMatch(Pattern pattern, String value) {
        return extractPatternMatch(pattern, value, 0);
    }

    private String extractPatternMatch(Pattern pattern, String value, int group) {
        if (pattern == null || value == null || value.isBlank()) {
            return null;
        }
        Matcher matcher = pattern.matcher(value);
        return matcher.find() ? matcher.group(group) : null;
    }

    private String canonicalizeUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme() == null ? "https" : uri.getScheme().toLowerCase(Locale.ENGLISH);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ENGLISH);
            String path = uri.getPath() == null || uri.getPath().isBlank() ? "" : uri.getPath().replaceAll("/+$", "");
            String query = canonicalIdentityQuery(uri.getRawQuery());
            StringBuilder canonical = new StringBuilder();
            canonical.append(scheme).append("://").append(host).append(path);
            if (query != null && !query.isBlank()) {
                canonical.append("?").append(query);
            }
            return canonical.toString();
        } catch (Exception e) {
            return url.trim();
        }
    }

    private String canonicalIdentityQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return null;
        }
        List<String> retained = new ArrayList<>();
        for (String token : rawQuery.split("&")) {
            String[] parts = token.split("=", 2);
            if (parts.length != 2) {
                continue;
            }
            String key = parts[0].toLowerCase(Locale.ENGLISH);
            if (Set.of("prid", "id", "relid", "releaseid").contains(key)) {
                retained.add(key + "=" + parts[1]);
            }
        }
        return retained.isEmpty() ? null : String.join("&", retained);
    }

    private String buildMetadataJson(String artifactUrl,
                                     String sourceUrl,
                                     String sourceReference,
                                     String feedTitle) {
        return "{"
                + "\"artifactUrl\":\"" + escapeJson(artifactUrl) + "\","
                + "\"canonicalSourceUrl\":\"" + escapeJson(sourceUrl) + "\","
                + "\"canonicalSourceReference\":\"" + escapeJson(sourceReference) + "\","
                + "\"feedTitle\":\"" + escapeJson(feedTitle) + "\""
                + "}";
    }

    private String cleanDocumentTitle(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return value
                .replaceAll("\\s*[|:-]\\s*(Reserve Bank of India|SEBI|Press Information Bureau.*)$", "")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength - 3)).trim() + "...";
    }

    private boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private void addImpact(Map<String, PolicyIntelligenceService.PolicyImpactDraft> impacts,
                           String key,
                           PolicyIntelligenceService.PolicyImpactDraft draft) {
        impacts.putIfAbsent(key, draft);
    }

    private String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public record SyncResult(
            String source,
            int documentsIngested,
            List<String> errors
    ) {
    }

    private record PolicyFetchPayload(
            String documentKey,
            PolicyAuthority authority,
            PolicyDocumentType documentType,
            PolicyBindingLevel bindingLevel,
            PolicyArea policyArea,
            String title,
            String sourceUrl,
            String sourceReference,
            String summary,
            LocalDate publishedDate,
            LocalDate effectiveFrom,
            LocalDateTime issuedAt,
            List<String> tags,
            List<String> affectedSectors,
            String metadataJson,
            String normalizedText,
            String externalDocumentId,
            List<PolicyIntelligenceService.PolicyImpactDraft> impacts
    ) {
    }

    private record PolicyEntryDraft(
            String title,
            String link,
            Date publishedDate
    ) {
    }
}
