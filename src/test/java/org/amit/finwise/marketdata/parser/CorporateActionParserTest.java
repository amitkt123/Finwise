package org.amit.finwise.marketdata.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.amit.finwise.marketdata.model.CorporateActionType;
import org.amit.finwise.marketdata.parser.CorporateActionParser.CaRecord;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parser tests against a real corporates-corporateActions excerpt captured
 * 2026-06-13 from www.nseindia.com.
 */
class CorporateActionParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private String fixture(String name) throws IOException {
        try (var in = getClass().getResourceAsStream("/marketdata/" + name)) {
            assertNotNull(in, "missing fixture " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private CaRecord bySymbol(List<CaRecord> rs, String symbol) {
        return rs.stream().filter(r -> r.symbol().equals(symbol)).findFirst().orElseThrow();
    }

    @Test
    void classifiesAndExtractsAllActionShapes() throws IOException {
        List<CaRecord> records = CorporateActionParser.parse(fixture("ca_sample.json"), mapper);
        assertFalse(records.isEmpty());

        // Face value split 10 -> 1
        CaRecord split = bySymbol(records, "NESTLEIND");
        assertEquals(CorporateActionType.SPLIT, split.actionType());
        assertEquals(0, new BigDecimal("10").compareTo(split.fromFaceValue()));
        assertEquals(0, new BigDecimal("1").compareTo(split.toFaceValue()));
        assertEquals(LocalDate.of(2024, 1, 5), split.exDate());

        // Bonus 3:1
        CaRecord bonus = bySymbol(records, "ALLCARGO");
        assertEquals(CorporateActionType.BONUS, bonus.actionType());
        assertEquals(3, bonus.ratioNew());
        assertEquals(1, bonus.ratioOld());

        // Rights 6:179
        CaRecord rights = bySymbol(records, "GRASIM");
        assertEquals(CorporateActionType.RIGHTS, rights.actionType());
        assertEquals(6, rights.ratioNew());
        assertEquals(179, rights.ratioOld());

        // Dividend with two components on one line: Rs 9 + Rs 18 = 27
        CaRecord tcs = bySymbol(records, "TCS");
        assertEquals(CorporateActionType.DIVIDEND, tcs.actionType());
        assertEquals(0, new BigDecimal("27").compareTo(tcs.dividendAmount()));

        // Simple single dividend
        CaRecord hcl = bySymbol(records, "HCLTECH");
        assertEquals(CorporateActionType.DIVIDEND, hcl.actionType());
        assertEquals(0, new BigDecimal("12").compareTo(hcl.dividendAmount()));

        // Buy Back / AGM fall through to OTHER, unparsed but retained
        assertEquals(CorporateActionType.OTHER, bySymbol(records, "DHAMPURSUG").actionType());
        assertEquals(CorporateActionType.OTHER, bySymbol(records, "SPICEJET").actionType());
    }

    @Test
    void classifyHandlesEdgeKeywords() {
        assertEquals(CorporateActionType.SPLIT,
                CorporateActionParser.classify("Face Value Split (Sub-Division) - From Rs10 To Re 1"));
        assertEquals(CorporateActionType.BONUS, CorporateActionParser.classify("Bonus 1:1"));
        assertEquals(CorporateActionType.RIGHTS, CorporateActionParser.classify("Rights 1:2 @ Premium Rs 5/-"));
        assertEquals(CorporateActionType.DIVIDEND, CorporateActionParser.classify("Interim Dividend - Rs 5 Per Share"));
        assertEquals(CorporateActionType.OTHER, CorporateActionParser.classify("Annual General Meeting"));
    }
}
