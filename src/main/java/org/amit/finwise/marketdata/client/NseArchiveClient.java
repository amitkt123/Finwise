package org.amit.finwise.marketdata.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads daily archive files from NSE's static archive hosts.
 *
 * Unlike www.nseindia.com API endpoints, the archive hosts
 * (archives.nseindia.com / nsearchives.nseindia.com) serve plain files and
 * need only a browser User-Agent — no cookie warm-up. A 404 means
 * "no file for that date" (weekend/holiday, or wrong format era) and is
 * returned as Optional.empty(); other failures throw.
 *
 * Format eras for the cash-market bhavcopy:
 *   - up to early Jul-2024:  cm{DD}{MMM}{YYYY}bhav.csv.zip   (old positional format)
 *   - from Jul-2024 onward:  BhavCopy_NSE_CM_..._{YYYYMMDD}_F_0000.csv.zip (UDiFF)
 */
@Slf4j
@Component
public class NseArchiveClient {

    private static final DateTimeFormatter OLD_FMT =
            DateTimeFormatter.ofPattern("ddMMMyyyy", Locale.ENGLISH);
    private static final DateTimeFormatter YYYYMMDD =
            DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter DDMMYYYY =
            DateTimeFormatter.ofPattern("ddMMyyyy");

    private final RestClient restClient;

    public NseArchiveClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(20));
        factory.setReadTimeout(Duration.ofSeconds(45));
        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("User-Agent",
                        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36")
                .defaultHeader("Accept", "*/*")
                .build();
    }

    /** Old-format cash-market bhavcopy CSV (unzipped), or empty on 404. */
    public Optional<String> fetchCmBhavcopyOld(LocalDate date) {
        String dateStr = OLD_FMT.format(date).toUpperCase(Locale.ENGLISH);
        String url = String.format(
                "https://archives.nseindia.com/content/historical/EQUITIES/%d/%s/cm%sbhav.csv.zip",
                date.getYear(),
                date.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH).toUpperCase(Locale.ENGLISH),
                dateStr);
        return fetchZippedCsv(url);
    }

    /** UDiFF cash-market bhavcopy CSV (unzipped), or empty on 404. */
    public Optional<String> fetchCmBhavcopyUdiff(LocalDate date) {
        String url = String.format(
                "https://nsearchives.nseindia.com/content/cm/BhavCopy_NSE_CM_0_0_0_%s_F_0000.csv.zip",
                YYYYMMDD.format(date));
        return fetchZippedCsv(url);
    }

    /**
     * sec_bhavdata_full CSV: full-market daily data with AVG_PRICE (VWAP) and
     * delivery quantity/percentage. One stable format from at least 2020 to
     * today. No ISIN column — joined to md_eod_price by symbol + date.
     */
    public Optional<String> fetchSecBhavdataFull(LocalDate date) {
        String url = String.format(
                "https://nsearchives.nseindia.com/products/content/sec_bhavdata_full_%s.csv",
                DDMMYYYY.format(date));
        byte[] body = fetchBytes(url);
        return body == null ? Optional.empty()
                : Optional.of(new String(body, StandardCharsets.UTF_8));
    }

    /** ind_close_all CSV (all NSE indices with P/E, P/B, div yield), or empty on 404. */
    public Optional<String> fetchIndexCloseAll(LocalDate date) {
        String url = String.format(
                "https://archives.nseindia.com/content/indices/ind_close_all_%s.csv",
                DDMMYYYY.format(date));
        byte[] body = fetchBytes(url);
        return body == null ? Optional.empty()
                : Optional.of(new String(body, StandardCharsets.UTF_8));
    }

    private Optional<String> fetchZippedCsv(String url) {
        byte[] zipBytes = fetchBytes(url);
        if (zipBytes == null) return Optional.empty();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry = zis.getNextEntry();
            if (entry == null) {
                throw new NseArchiveException("Empty zip from " + url);
            }
            return Optional.of(new String(zis.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new NseArchiveException("Failed to unzip " + url + ": " + e.getMessage(), e);
        }
    }

    /** Returns body bytes, null on 404, throws on other errors. */
    private byte[] fetchBytes(String url) {
        try {
            return restClient.get().uri(url).retrieve().body(byte[].class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) return null;
            throw new NseArchiveException("HTTP " + e.getStatusCode().value() + " from " + url, e);
        } catch (RestClientException e) {
            throw new NseArchiveException("Request failed for " + url + ": " + e.getMessage(), e);
        }
    }

    public static class NseArchiveException extends RuntimeException {
        public NseArchiveException(String message) { super(message); }
        public NseArchiveException(String message, Throwable cause) { super(message, cause); }
    }
}
