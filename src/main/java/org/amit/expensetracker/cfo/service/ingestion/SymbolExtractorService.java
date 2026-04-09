package org.amit.expensetracker.cfo.service.ingestion;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tier 2: Named-entity extraction for Indian stock symbols.
 * <p>
 * Uses a multi-layer approach:
 *   1. Gazetteer lookup  – company name / alias → NSE symbol + ISIN
 *   2. Regex patterns    – catches "HDFCBANK", "TCS.NS", "BSE:500325"
 *   3. Sector tagging    – maps matched companies to sectors
 * <p>
 * The gazetteer is loaded from a CSV at startup. You can seed it from the
 * NSE bhav-copy or a static export and keep it in src/main/resources.
 * <p>
 * CSV format (no header):
 *   SYMBOL,ISIN,SECTOR,alias1|alias2|alias3
 *   HDFCBANK,INE040A01034,Banking,HDFC Bank|HDFC Bank Ltd|hdfc bank limited
 */
@Slf4j
@Service
public class SymbolExtractorService {

    @Value("${cfo.symbols.gazetteer-path:classpath:data/symbol_gazetteer.csv}")
    private Resource gazetteerResource;

    // ── Lookup structures ─────────────────────────────────────────────────────

    /** Alias (lowercased) → SymbolEntry */
    private final Map<String, SymbolEntry> aliasTrie = new HashMap<>();

    /** NSE symbol (uppercase) → SymbolEntry */
    private final Map<String, SymbolEntry> symbolMap = new HashMap<>();

    /** All known aliases sorted longest-first (for greedy text matching) */
    private List<String> sortedAliases = List.of();

    /**
     * Pre-compiled word-boundary pattern per alias.
     * Built once in init() — Pattern is thread-safe for concurrent Matcher use.
     *
     * Why pre-compiled \b patterns instead of manual indexOf + char checks:
     *   1. Java's \b correctly handles ALL boundary types (Java word-char = [A-Za-z0-9_]).
     *      The manual check only tested isLetterOrDigit(), missing '_'.
     *   2. Pattern.quote() escapes special chars in multi-word aliases (e.g. "m&m", "l and t").
     *   3. Compile-once vs per-article indexOf loop — faster at scale.
     *   4. Critical for 2-3 char aliases like "vi", "lt", "hal", "bel":
     *      \bvi\b won't fire inside "vital", \blt\b won't fire inside "fault",
     *      \bhal\b won't fire inside "halted", \bbel\b won't fire inside "rebel".
     */
    private Map<String, Pattern> aliasPatterns = new HashMap<>();

    // ── Regex patterns for explicit ticker mentions ───────────────────────────
    //  Matches: "HDFCBANK", "TCS.NS", "BSE:500325", "NSE:INFY", "$RELIANCE"
    private static final Pattern TICKER_PATTERN = Pattern.compile(
            "(?i)(?:NSE:|BSE:|\\$)([A-Z]{2,20})" +           // NSE:INFY or $RELIANCE
            "|\\b([A-Z]{2,15})\\.(?:NS|BO)\\b" +              // HDFCBANK.NS / TCS.BO
            "|\\bBSE:(\\d{5,6})\\b"                            // BSE:500325
    );

    // ── Sector keywords (fallback when no direct match) ───────────────────────
    private static final Map<String, List<String>> SECTOR_KEYWORDS = Map.ofEntries(

            // ── Banking & Financials ───────────────────────────────────────────────
            Map.entry("Banking", List.of(
                    "bank", "banking", "npa", "credit growth", "casa", "nbfc",
                    "loan growth", "deposit growth", "net interest margin", "nim",
                    "liquidity", "repo rate", "rate hike", "rate cut", "monetary policy",
                    "yield", "bond yield", "treasury yield", "rbi policy"
            )),

            // ── IT / Tech ──────────────────────────────────────────────────────────
            Map.entry("IT", List.of(
                    "it sector", "tech sector", "software", "saas", "cloud", "ai",
                    "digital transformation", "outsourcing", "it services",
                    "semiconductor", "chip shortage", "helium shortage", "data center"
            )),

            // ── Pharma / Healthcare ────────────────────────────────────────────────
            Map.entry("Pharma", List.of(
                    "pharma", "drug", "fda", "anda", "api", "clinical trial",
                    "us fda approval", "generic drugs", "biosimilar", "vaccine",
                    "healthcare", "hospital chain"
            )),

            // ── Auto / EV ─────────────────────────────────────────────────────────
            Map.entry("Auto", List.of(
                    "auto", "automobile", "ev", "electric vehicle", "car sales",
                    "two wheeler", "four wheeler", "vehicle registration",
                    "lithium", "battery", "charging infrastructure"
            )),

            // ── FMCG / Consumption ────────────────────────────────────────────────
            Map.entry("FMCG", List.of(
                    "fmcg", "consumer goods", "consumer staples",
                    "inflation", "input cost", "margin pressure",
                    "edible oil", "palm oil", "wheat", "food inflation",
                    "rural demand", "urban demand", "pricing power"
            )),

            // ── Energy / Oil & Gas ────────────────────────────────────────────────
            Map.entry("Energy", List.of(
                    "crude oil", "brent", "wti", "natural gas",
                    "lng", "refinery", "opec", "oil production",
                    "oil price", "energy crisis", "fuel price"
            )),

            // ── Metals & Mining ───────────────────────────────────────────────────
            Map.entry("Metals", List.of(
                    "steel", "aluminium", "copper", "zinc",
                    "iron ore", "coking coal", "metal prices",
                    "mining", "commodity cycle"
            )),

            // ── Realty / Housing ──────────────────────────────────────────────────
            Map.entry("Realty", List.of(
                    "real estate", "realty", "housing",
                    "mortgage", "home loan", "property market",
                    "housing demand", "interest rate sensitive"
            )),

            // ── Telecom ───────────────────────────────────────────────────────────
            Map.entry("Telecom", List.of(
                    "telecom", "5g", "spectrum", "arpu",
                    "subscriber base", "data usage", "tariff hike"
            )),

            // ── Infrastructure / Capex ────────────────────────────────────────────
            Map.entry("Infrastructure", List.of(
                    "infrastructure", "infra", "capex", "government spending",
                    "highway", "railway", "logistics", "smart city",
                    "pli scheme", "capital expenditure"
            )),

            // ── Textile ───────────────────────────────────────────────────────────
            Map.entry("Textile", List.of(
                    "textile", "cotton", "synthetic fibre",
                    "garment", "apparel", "export demand",
                    "yarn", "spinning", "fabric"
            )),

            // ── Chemicals ─────────────────────────────────────────────────────────
            Map.entry("Chemicals", List.of(
                    "chemical", "specialty chemical", "agrochemical",
                    "fertiliser", "fertilizer", "petrochemical",
                    "input cost", "feedstock"
            )),

            // ── Defence ───────────────────────────────────────────────────────────
            Map.entry("Defence", List.of(
                    "defence", "military", "war", "conflict",
                    "fighter jet", "missile", "drdo",
                    "defence deal", "arms export"
            )),

            // ── Aviation ──────────────────────────────────────────────────────────
            Map.entry("Aviation", List.of(
                    "aviation", "airline", "air traffic",
                    "jet fuel", "atf price", "passenger traffic"
            )),

            // ── Cement ────────────────────────────────────────────────────────────
            Map.entry("Cement", List.of(
                    "cement", "clinker", "construction",
                    "infra demand", "real estate demand"
            )),

            // ── Retail / E-commerce ───────────────────────────────────────────────
            Map.entry("Retail", List.of(
                    "retail", "ecommerce", "online shopping",
                    "store expansion", "footfall", "quick commerce",
                    "blinkit", "zepto", "bigbasket"
            )),

            // ── Capital Markets ───────────────────────────────────────────────────
            Map.entry("Capital Markets", List.of(
                    "stock market", "equity market", "ipo",
                    "fii", "dii", "market rally", "selloff",
                    "valuation", "earnings growth"
            ))
    );

    /**
     * Negative Constraint Matrix: if the article's Tier 1 category is a strong domain signal,
     * these "noisy" sectors are suppressed UNLESS backed by a direct company name match
     * from the gazetteer (not just a keyword hit).
     *
     * Prevents false positives like:
     *   - "api" keyword firing Pharma on an IT/Energy article
     *   - "issues" in "Rights Issues" matching a Pharma alias
     *   - Geopolitical articles picking up Realty via "interest rate sensitive" keyword
     */
    private static final Map<String, Set<String>> CATEGORY_SUPPRESSED_SECTORS = Map.of(
        "ENERGY",           Set.of("Pharma", "Realty", "Textile"),
        "GEOPOLITICAL",     Set.of("Pharma", "Realty"),
        "MACRO_RISK",       Set.of("Pharma", "Realty"),
        "REGULATORY",       Set.of("Pharma"),
        "MACRO",            Set.of("Pharma")
    );

    /**
     * Commodity / macro event → implicitly affected sectors and representative stocks.
     * This is the supply-chain resolution layer: articles that mention a commodity
     * without naming specific companies still get sector tags and representative
     * symbols so the LLM has context to reason about portfolio impact.
     *
     * Format: trigger phrase → {sector → [top NSE symbols likely impacted]}
     */
    private static final Map<String, Map<String, List<String>>> COMMODITY_IMPACT_MAP = Map.ofEntries(
        Map.entry("helium shortage",     Map.of("IT", List.of("TCS","INFY","HCLTECH","WIPRO"))),
        Map.entry("cotton price",        Map.of("Textile", List.of("RAYMOND","ARVIND","WELSPUNIND"))),
        Map.entry("cotton import duty",  Map.of("Textile", List.of("RAYMOND","ARVIND","WELSPUNIND"))),
        Map.entry("synthetic fibre",     Map.of("Textile", List.of("GNFC","SRF","RELIANCE"))),
        Map.entry("manmade fibre",       Map.of("Textile", List.of("GNFC","SRF","RELIANCE"))),
        Map.entry("lithium price",       Map.of("Auto",    List.of("TATAMOTORS","M&M","BAJAJ-AUTO"))),
        Map.entry("copper price",        Map.of("Metals",  List.of("HINDALCO","STERLITETECH"))),
        Map.entry("iron ore price",      Map.of("Metals",  List.of("TATASTEEL","JSWSTEEL","NMDC"))),
        Map.entry("coking coal",         Map.of("Metals",  List.of("TATASTEEL","JSWSTEEL","COALINDIA"))),
        Map.entry("palm oil price",      Map.of("FMCG",    List.of("HINDUNILVR","ITC","NESTLEIND"))),
        Map.entry("edible oil price",    Map.of("FMCG",    List.of("HINDUNILVR","ITC","MARICO"))),
        Map.entry("wheat price",         Map.of("FMCG",    List.of("ITC","BRITANNIA","NESTLEIND"))),
        Map.entry("jet fuel",            Map.of("Aviation",List.of("INDIGO","SPICEJET"))),
        Map.entry("atf price",           Map.of("Aviation",List.of("INDIGO","SPICEJET"))),
        Map.entry("natural rubber",      Map.of("Auto",    List.of("APOLLOTYRE","CEATLTD","MRF"))),
        Map.entry("semiconductor",       Map.of("IT",      List.of("TCS","INFY","HCLTECH","WIPRO")))
    );

    // ══════════════════════════════════════════════════════════════════════════
    //  Initialisation
    // ══════════════════════════════════════════════════════════════════════════

    @PostConstruct
    public void init() {
        loadGazetteer();
        loadBuiltinSymbols();  // hardcoded top-100 as safety net
        sortedAliases = aliasTrie.keySet().stream()
                .sorted((a, b) -> Integer.compare(b.length(), a.length()))
                .toList();

        // Pre-compile a word-boundary pattern for every alias.
        // Pattern.quote() handles special chars in aliases like "m&m", "dr. reddy".
        // CASE_INSENSITIVE because input text arrives in lowercase already, but
        // keeping the flag makes the patterns reusable against mixed-case input too.
        for (String alias : aliasTrie.keySet()) {
            aliasPatterns.put(alias,
                    Pattern.compile("\\b" + Pattern.quote(alias) + "\\b",
                            Pattern.CASE_INSENSITIVE));
        }

        log.info("SymbolExtractor initialised: {} aliases, {} symbols, {} patterns compiled",
                aliasTrie.size(), symbolMap.size(), aliasPatterns.size());
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Public API
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Extract all stock symbols and sectors mentioned in a piece of text.
     */
    public ExtractionResult extract(String title, String summary) {
        return extract(title, summary, null);
    }

    /**
     * Extract all stock symbols and sectors mentioned in a piece of text.
     *
     * @param tier1Category Tier 1 category (e.g. "ENERGY", "GEOPOLITICAL") used to apply
     *                      negative constraints — suppresses ambiguous sector tags that have
     *                      no direct company name backing in articles dominated by another domain.
     */
    public ExtractionResult extract(String title, String summary, String tier1Category) {
        String text = (title + " " + summary);
        String textLower = text.toLowerCase();

        Set<SymbolMatch> matches = new LinkedHashSet<>();

        // 1. Regex: explicit ticker patterns like NSE:INFY, $RELIANCE, TCS.NS
        extractByRegex(text, matches);

        // 2. Gazetteer: longest-match scan for company names / aliases
        extractByGazetteer(textLower, matches);

        // Sectors directly backed by a gazetteer company match — these are always trusted
        Set<String> companySectors = matches.stream()
                .map(m -> m.entry.sector)
                .filter(Objects::nonNull)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // 3. Sector keywords (even if no specific company found)
        Set<String> sectors = new LinkedHashSet<>(companySectors);

        // Add sector-level mentions even without a specific company.
        // Use word-boundary matching to prevent short keywords like "api" from
        // matching inside unrelated words (e.g. "capital", "reaping").
        for (Map.Entry<String, List<String>> entry : SECTOR_KEYWORDS.entrySet()) {
            for (String kw : entry.getValue()) {
                if (containsWord(textLower, kw)) {
                    sectors.add(entry.getKey());
                    break;
                }
            }
        }

        // Supply-chain resolution: commodity mentions → implied sectors + representative symbols
        // e.g. "helium shortage" → IT sector + TCS/INFY/HCLTECH as representative symbols
        for (Map.Entry<String, Map<String, List<String>>> ce : COMMODITY_IMPACT_MAP.entrySet()) {
            if (textLower.contains(ce.getKey())) {
                for (Map.Entry<String, List<String>> se : ce.getValue().entrySet()) {
                    sectors.add(se.getKey());
                    // Add representative symbols only if we don't already have direct matches
                    // (avoid polluting an article that already has specific company mentions)
                    if (matches.isEmpty()) {
                        for (String sym : se.getValue()) {
                            SymbolEntry entry = symbolMap.get(sym);
                            if (entry != null) matches.add(new SymbolMatch(entry, "COMMODITY_CHAIN"));
                        }
                    }
                }
            }
        }

        // ── Negative Constraint: category-based sector suppression ────────────
        // If Tier 1 strongly signals a domain (ENERGY, GEOPOLITICAL, etc.), remove
        // ambiguous sector tags that are only keyword-matched with no direct company
        // backing. Prevents "api" from injecting Pharma into an Energy article.
        if (tier1Category != null) {
            Set<String> suppressed = CATEGORY_SUPPRESSED_SECTORS.getOrDefault(
                    tier1Category.toUpperCase(), Set.of());
            if (!suppressed.isEmpty()) {
                sectors.removeIf(s -> suppressed.contains(s) && !companySectors.contains(s));
                if (!suppressed.isEmpty()) {
                    log.debug("Suppressed sectors {} for category {} (no direct company match)",
                            suppressed, tier1Category);
                }
            }
        }

        // Build result
        List<String> symbols = matches.stream().map(m -> m.entry.symbol).distinct().toList();
        List<String> isins   = matches.stream().map(m -> m.entry.isin).filter(Objects::nonNull).distinct().toList();

        return new ExtractionResult(symbols, isins, new ArrayList<>(sectors), matches.size());
    }

    /**
     * Check if a specific symbol or its aliases appear in the text.
     */
    public boolean mentionsSymbol(String text, String nseSymbol) {
        SymbolEntry entry = symbolMap.get(nseSymbol.toUpperCase());
        if (entry == null) return text.toUpperCase().contains(nseSymbol.toUpperCase());

        String lower = text.toLowerCase();
        for (String alias : entry.aliases) {
            if (lower.contains(alias.toLowerCase())) return true;
        }
        return lower.contains(nseSymbol.toLowerCase());
    }

    // ── Extraction Strategies ─────────────────────────────────────────────────

    /**
     * Returns true only if {@code keyword} appears in {@code text} at a word boundary
     * (not as a substring of a longer word). This prevents short keywords like "api"
     * from matching inside "capital" or "reaping".
     */
    private static boolean containsWord(String text, String keyword) {
        int idx = text.indexOf(keyword);
        while (idx >= 0) {
            boolean leftOk  = idx == 0 || !Character.isLetterOrDigit(text.charAt(idx - 1));
            boolean rightOk = idx + keyword.length() >= text.length()
                           || !Character.isLetterOrDigit(text.charAt(idx + keyword.length()));
            if (leftOk && rightOk) return true;
            idx = text.indexOf(keyword, idx + 1);
        }
        return false;
    }

    private void extractByRegex(String text, Set<SymbolMatch> matches) {
        Matcher matcher = TICKER_PATTERN.matcher(text);
        while (matcher.find()) {
            String symbol = matcher.group(1) != null ? matcher.group(1)
                          : matcher.group(2) != null ? matcher.group(2)
                          : null;  // group 3 is BSE code, handled separately

            if (symbol != null) {
                symbol = symbol.toUpperCase();
                SymbolEntry entry = symbolMap.get(symbol);
                if (entry != null) {
                    matches.add(new SymbolMatch(entry, "REGEX"));
                } else {
                    // Unknown symbol – still record it, the LLM tier can verify
                    matches.add(new SymbolMatch(
                            new SymbolEntry(symbol, null, null, List.of()), "REGEX_UNVERIFIED"));
                }
            }

            // BSE code match
            String bseCode = matcher.group(3);
            if (bseCode != null) {
                // Look up by BSE code if we have a reverse map (extension point)
                matches.add(new SymbolMatch(
                        new SymbolEntry("BSE:" + bseCode, null, null, List.of()), "REGEX_BSE"));
            }
        }
    }

    private void extractByGazetteer(String textLower, Set<SymbolMatch> matches) {
        // coveredPositions prevents a shorter alias from re-consuming text already
        // claimed by a longer alias (greedy longest-match, enforced by sortedAliases order).
        Set<Integer> coveredPositions = new HashSet<>();

        for (String alias : sortedAliases) {
            Pattern pattern = aliasPatterns.get(alias);
            if (pattern == null) continue;  // shouldn't happen post-init

            Matcher m = pattern.matcher(textLower);
            while (m.find()) {
                int start = m.start();
                int end   = m.end();

                // Check overlap with already-claimed positions (longer alias won)
                boolean overlap = false;
                for (int p = start; p < end; p++) {
                    if (coveredPositions.contains(p)) { overlap = true; break; }
                }
                if (!overlap) {
                    for (int p = start; p < end; p++) coveredPositions.add(p);
                    SymbolEntry entry = aliasTrie.get(alias);
                    if (entry != null) {
                        matches.add(new SymbolMatch(entry, "GAZETTEER"));
                    }
                }
            }
        }
    }

    // ── Gazetteer Loading ─────────────────────────────────────────────────────

    private void loadGazetteer() {
        try {
            if (!gazetteerResource.exists()) {
                log.warn("Gazetteer CSV not found at configured path; using builtins only");
                return;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(gazetteerResource.getInputStream(), StandardCharsets.UTF_8))) {
                reader.lines()
                        .filter(line -> !line.isBlank() && !line.startsWith("#"))
                        .forEach(this::parseGazetteerLine);
            }
        } catch (Exception e) {
            log.warn("Failed to load gazetteer CSV: {}", e.getMessage());
        }
    }

    private void parseGazetteerLine(String line) {
        // FORMAT: SYMBOL,ISIN,SECTOR,alias1|alias2|alias3
        String[] parts = line.split(",", 4);
        if (parts.length < 1) return;

        String symbol = parts[0].trim().toUpperCase();
        String isin   = parts.length > 1 ? parts[1].trim() : null;
        String sector = parts.length > 2 ? parts[2].trim() : null;

        List<String> aliases = new ArrayList<>();
        aliases.add(symbol.toLowerCase());
        if (parts.length > 3) {
            for (String a : parts[3].split("\\|")) {
                String trimmed = a.trim().toLowerCase();
                if (!trimmed.isBlank()) aliases.add(trimmed);
            }
        }

        registerSymbol(symbol, isin, sector, aliases);
    }

    /**
     * Hardcoded top companies so the service works even without the CSV.
     * In production, keep the CSV up-to-date from NSE bhav-copy.
     */
    private void loadBuiltinSymbols() {
        // Nifty 50 top holdings + commonly discussed stocks
        registerSymbol("HDFCBANK",  "INE040A01034", "Banking",
                List.of("hdfcbank", "hdfc bank", "hdfc bank ltd"));
        registerSymbol("ICICIBANK", "INE090A01021", "Banking",
                List.of("icicibank", "icici bank", "icici bank ltd"));
        registerSymbol("SBIN",      "INE062A01020", "Banking",
                List.of("sbin", "sbi", "state bank of india", "state bank"));
        registerSymbol("KOTAKBANK", "INE237A01028", "Banking",
                List.of("kotakbank", "kotak mahindra bank", "kotak bank"));
        registerSymbol("AXISBANK",  "INE238A01034", "Banking",
                List.of("axisbank", "axis bank"));
        registerSymbol("INDUSINDBK","INE095A01012", "Banking",
                List.of("indusindbk", "indusind bank"));

        registerSymbol("RELIANCE",  "INE002A01018", "Energy",
                List.of("reliance", "reliance industries", "ril"));
        registerSymbol("TCS",       "INE467B01029", "IT",
                List.of("tcs", "tata consultancy", "tata consultancy services"));
        registerSymbol("INFY",      "INE009A01021", "IT",
                List.of("infy", "infosys", "infosys ltd"));
        registerSymbol("WIPRO",     "INE075A01022", "IT",
                List.of("wipro", "wipro ltd"));
        registerSymbol("HCLTECH",   "INE860A01027", "IT",
                List.of("hcltech", "hcl tech", "hcl technologies"));
        registerSymbol("TECHM",     "INE669C01036", "IT",
                List.of("techm", "tech mahindra"));
        registerSymbol("LTIMindtree","INE214T01019", "IT",
                List.of("ltimindtree", "lti mindtree", "ltim"));

        registerSymbol("HINDUNILVR","INE030A01027", "FMCG",
                List.of("hindunilvr", "hul", "hindustan unilever"));
        registerSymbol("ITC",       "INE154A01025", "FMCG",
                List.of("itc", "itc ltd"));
        registerSymbol("NESTLEIND", "INE239A01016", "FMCG",
                List.of("nestleind", "nestle india", "nestle"));
        registerSymbol("BRITANNIA", "INE216A01030", "FMCG",
                List.of("britannia", "britannia industries"));

        registerSymbol("BHARTIARTL","INE397D01024", "Telecom",
                List.of("bhartiartl", "airtel", "bharti airtel"));
        registerSymbol("JIOFINANCE","INE758E01017", "Financial Services",
                List.of("jiofinance", "jio financial", "jio finance"));

        registerSymbol("TATAMOTORS","INE155A01022", "Auto",
                List.of("tatamotors", "tata motors"));
        registerSymbol("MARUTI",    "INE585B01010", "Auto",
                List.of("maruti", "maruti suzuki"));
        registerSymbol("M&M",       "INE101A01026", "Auto",
                List.of("m&m", "mahindra", "mahindra & mahindra"));
        registerSymbol("BAJAJ-AUTO","INE917I01010", "Auto",
                List.of("bajaj-auto", "bajaj auto"));

        registerSymbol("SUNPHARMA", "INE044A01036", "Pharma",
                List.of("sunpharma", "sun pharma", "sun pharmaceutical"));
        registerSymbol("DRREDDY",   "INE089A01023", "Pharma",
                List.of("drreddy", "dr reddy", "dr. reddy", "dr reddys"));
        registerSymbol("CIPLA",     "INE059A01026", "Pharma",
                List.of("cipla"));

        registerSymbol("TATASTEEL", "INE081A01020", "Metals",
                List.of("tatasteel", "tata steel"));
        registerSymbol("HINDALCO",  "INE038A01020", "Metals",
                List.of("hindalco", "hindalco industries"));
        registerSymbol("JSWSTEEL",  "INE019A01038", "Metals",
                List.of("jswsteel", "jsw steel"));

        registerSymbol("ADANIENT",  "INE423A01024", "Infrastructure",
                List.of("adanient", "adani enterprises", "adani"));
        registerSymbol("ADANIPORTS","INE742F01042", "Infrastructure",
                List.of("adaniports", "adani ports"));

        registerSymbol("POWERGRID", "INE752E01010", "Power",
                List.of("powergrid", "power grid", "power grid corporation"));
        registerSymbol("NTPC",      "INE733E01010", "Power",
                List.of("ntpc", "ntpc ltd"));

        registerSymbol("BAJFINANCE","INE296A01024", "Financial Services",
                List.of("bajfinance", "bajaj finance"));
        registerSymbol("BAJAJFINSV","INE918I01018", "Financial Services",
                List.of("bajajfinsv", "bajaj finserv"));

        registerSymbol("LTIM",      "INE214T01019", "IT",
                List.of("lti mindtree")); // duplicate aliases OK, same entry

        registerSymbol("ASIANPAINT","INE021A01026", "Consumer Discretionary",
                List.of("asianpaint", "asian paints"));
        registerSymbol("TITAN",     "INE280A01028", "Consumer Discretionary",
                List.of("titan", "titan company"));
        registerSymbol("ULTRACEMCO","INE481G01011", "Cement",
                List.of("ultracemco", "ultratech cement", "ultratech"));

        registerSymbol("ONGC",      "INE213A01029", "Energy",
                List.of("ongc", "oil and natural gas"));
        registerSymbol("COALINDIA", "INE522F01014", "Mining",
                List.of("coalindia", "coal india"));

        registerSymbol("DIVISLAB",  "INE361B01024", "Pharma",
                List.of("divislab", "divis lab", "divi's lab", "divi's laboratories"));
        registerSymbol("APOLLOHOSP","INE437A01024", "Healthcare",
                List.of("apollohosp", "apollo hospitals", "apollo hospital"));

        log.debug("Loaded {} builtin symbol entries", symbolMap.size());
    }

    private void registerSymbol(String symbol, String isin, String sector, List<String> aliases) {
        SymbolEntry entry = symbolMap.computeIfAbsent(symbol,
                k -> new SymbolEntry(symbol, isin, sector, new ArrayList<>(aliases)));

        // Merge aliases if entry already exists
        for (String alias : aliases) {
            String key = alias.toLowerCase();
            aliasTrie.putIfAbsent(key, entry);
            if (!entry.aliases.contains(key)) {
                entry.aliases.add(key);
            }
        }
    }

    // ── Result types ──────────────────────────────────────────────────────────

    public record ExtractionResult(
            List<String> symbols,       // ["HDFCBANK", "SBIN"]
            List<String> isins,         // ["INE040A01034", "INE062A01020"]
            List<String> sectors,       // ["Banking", "IT"]
            int          matchCount     // total entity matches found
    ) {}

    public record SymbolMatch(SymbolEntry entry, String matchType) {
        @Override
        public boolean equals(Object o) {
            return o instanceof SymbolMatch sm && sm.entry.symbol.equals(entry.symbol);
        }
        @Override
        public int hashCode() { return entry.symbol.hashCode(); }
    }

    public static class SymbolEntry {
        public final String symbol;
        public final String isin;
        public final String sector;
        public final List<String> aliases;

        public SymbolEntry(String symbol, String isin, String sector, List<String> aliases) {
            this.symbol = symbol;
            this.isin = isin;
            this.sector = sector;
            this.aliases = aliases;
        }
    }
}
