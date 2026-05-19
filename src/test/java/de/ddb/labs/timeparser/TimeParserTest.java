package de.ddb.labs.timeparser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.ddb.labs.timeparser.model.ParseErrorStats;
import de.ddb.labs.timeparser.model.ParseResult;
import de.ddb.labs.timeparser.timespan.TimeSpan;
import de.ddb.labs.timeparser.timespan.TimeSpanParser;

import java.time.LocalDate;
import java.time.temporal.JulianFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regressionstests für Parser-Semantik, Fehlertoleranz und allgemeine Robustheit.
 *
 * <p>CSV-gestützte Regelvalidierung und CSV-Integritätsprüfungen befinden sich
 * in {@link RulesTest} bzw. {@link CsvIntegrityTest}. HTTP-Serialisierungstests
 * befinden sich in {@link HttpTest}.</p>
 */
public class TimeParserTest {

    @Test
    @DisplayName("[Steps 1-6] Parses canonical ISO-like input into facet and day-range payload")
    public void parsesSimpleInput() {
        assertEquals("time_18000 -5583373|-5583373", TimeParser.getInstance().parseTime("-20000-02-21"));
    }

    @Test
    @DisplayName("[Step 6b] Supports legacy day-index output via explicit parse overload")
    public void parsesSimpleInputWithLegacyIndexDaysMode() {
        assertEquals(
            "time_18000 -7304949|-7304949",
            TimeParser.getInstance().parseTime("-20000-02-21", TimeParser.IndexDaysMode.LEGACY));
    }

    @Test
    @DisplayName("[Step 5] Applies BC era suffix to both range boundaries")
    public void parsesBcDateSuffixForWholeRange() {
        final TimeSpan timeSpan = new TimeSpanParser().parse("100/101 vor Christus");

        assertEquals(LocalDate.of(-99, 1, 1), timeSpan.getStartDate());
        assertEquals(LocalDate.of(-100, 12, 31), timeSpan.getEndDate());
    }

    @Test
    @DisplayName("[Steps 1-6] Exposes structured parse result metadata for successful parsing")
    public void exposesStructuredParseResult() {
        final ParseResult result = TimeParser.getInstance().parseTimeResult("-20000-02-21");

        assertTrue(result.isSuccessful());
        assertEquals("-20000-02-21", result.getInput());
        assertEquals(TimeParser.IndexDaysMode.JULIAN_DAY, result.getIndexDaysMode());
        assertEquals("time_18000 -5583373|-5583373", result.getOutput());
        assertEquals("time_18000", result.getFacetString());
        assertEquals(-5583373L, result.getStartIndexDay());
        assertEquals(-5583373L, result.getEndIndexDay());
        assertNotNull(result.getTimeSpan());
        assertTrue(result.getFacetNotations().stream().anyMatch(facet -> "time_18000".equals(facet.getNotation())
                && "Quartär".equals(facet.getPrefLabelDe())
                && "Quaternary".equals(facet.getPrefLabelEn())));
        assertTrue(result.getErrorType() == null);
        assertTrue(result.getErrorMessage() == null);
    }

    @Test
    @DisplayName("[Steps 1-6] Exposes structured failure metadata instead of only an empty string")
    public void exposesStructuredFailureResult() {
        final ParseResult result = TimeParser.getInstance().parseTimeResult("1000000000");

        assertFalse(result.isSuccessful());
        assertEquals("1000000000", result.getInput());
        assertEquals("", result.getOutput());
        assertEquals("DateTimeException", result.getErrorType());
        assertNotNull(result.getErrorMessage());
    }

    @Test
    @DisplayName("[Step 1] Normalizes BCE abbreviation variants before rule matching")
    public void normalizesBceAbbreviationVariants() {
        final TimeParser parser = TimeParser.getInstance();
        final String expected = parser.parseTime("-200000000");

        assertEquals(expected, parser.parseTime("200000000 v.Chr."));
        assertEquals(expected, parser.parseTime("200000000 v. Chr."));
        assertEquals(expected, parser.parseTime("200000000 v. Chr"));
        assertEquals(expected, parser.parseTime("200000000 v Chr."));
        assertEquals(expected, parser.parseTime("200000000 v Chr"));
    }

    @Test
    @DisplayName("[Steps 5-6] Parses and generates the largest supported positive year")
    public void parsesMaximumSupportedPositiveYear() {
        final TimeSpan timeSpan = new TimeSpanParser().parse("999999999");

        assertEquals(LocalDate.of(999999999, 1, 1), timeSpan.getStartDate());
        assertEquals(LocalDate.of(999999999, 12, 31), timeSpan.getEndDate());
        assertTrue(TimeParser.getInstance().parseTime("999999999").endsWith(expectedIndexRange(timeSpan)));
    }

    @Test
    @DisplayName("[Steps 5-6] Parses and generates the largest supported BCE year in current negative notation")
    public void parsesMaximumSupportedNegativeYearOfEra() {
        final TimeSpan timeSpan = new TimeSpanParser().parse("-1000000000");

        assertEquals(LocalDate.of(-999999999, 1, 1), timeSpan.getStartDate());
        assertEquals(LocalDate.of(-999999999, 12, 31), timeSpan.getEndDate());
        assertTrue(TimeParser.getInstance().parseTime("-1000000000").endsWith(expectedIndexRange(timeSpan)));
    }

    @Test
    @DisplayName("[Step 1] Normalizes large-number unit variants before rule matching")
    public void normalizesLargeNumberUnitVariants() {
        final TimeParser parser = TimeParser.getInstance();

        final ParseResult million = parser.parseTimeResult("vor 500 Millionen Jahren");
        assertEquals("vor 500 Mio. Jahren", million.getNormalizedInput());
        assertEquals(parser.parseTime("-500000000"), million.getOutput());

        final ParseResult milliard = parser.parseTimeResult("vor 1 Milliarde Jahren");
        assertEquals("vor 1 Mrd. Jahren", milliard.getNormalizedInput());
        assertEquals(parser.parseTime("-1000000000"), milliard.getOutput());

        final ParseResult shorthandMilliard = parser.parseTimeResult("vor 1 Mrd Jahren");
        assertEquals("vor 1 Mrd. Jahren", shorthandMilliard.getNormalizedInput());
        assertEquals(parser.parseTime("-1000000000"), shorthandMilliard.getOutput());

        final ParseResult billion = parser.parseTimeResult("vor 1 Bill. Jahren");
        assertEquals("vor 1 Billionen Jahren", billion.getNormalizedInput());
        assertEquals("NumberFormatException", billion.getErrorType());
    }

    @Test
    @DisplayName("[Steps 1-4] Parses million-year past expressions through rules.csv normalization")
    public void parsesMillionYearPastExpressions() {
        final TimeParser parser = TimeParser.getInstance();

        assertEquals(parser.parseTime("-500000000"), parser.parseTime("vor 500 Mio. Jahren"));
        assertEquals(parser.parseTime("-1000000000"), parser.parseTime("vor 1000 Millionen Jahren"));
    }

    @Test
    @DisplayName("[Steps 1-4] Parses million-year future expressions while resulting year stays within LocalDate range")
    public void parsesMillionYearFutureExpressionsWithinRange() {
        final TimeParser parser = TimeParser.getInstance();

        assertEquals(parser.parseTime("250000000"), parser.parseTime("in 250 Mio Jahren"));
        assertEquals(parser.parseTime("250000000"), parser.parseTime("in 250 Millionen Jahren"));
    }

    @Test
    @DisplayName("[Step 5] Returns empty output for years outside LocalDate range")
    public void returnsEmptyStringForYearsOutsideSupportedRange() {
        assertEquals("", TimeParser.getInstance().parseTime("1000000000"));
        assertEquals("", TimeParser.getInstance().parseTime("-1000000001"));
    }

    @Test
    @DisplayName("[Steps 1, 5] Returns empty output when million-year future expressions exceed LocalDate range")
    public void returnsEmptyStringForUnsupportedFutureMillionYearExpressions() {
        assertEquals("", TimeParser.getInstance().parseTime("in 1000 Mio Jahren"));
        assertEquals("", TimeParser.getInstance().parseTime("in 1000 Millionen Jahren"));
    }

    @Test
    @DisplayName("[Steps 1, 5] Returns empty output for billion-year expressions because the resulting year exceeds LocalDate range")
    public void returnsEmptyStringForUnsupportedBillionYearExpressions() {
        assertEquals("", TimeParser.getInstance().parseTime("vor 1 Billion Jahren"));
        assertEquals("", TimeParser.getInstance().parseTime("in 1 Billionen Jahren"));
    }

    @Test
    @DisplayName("[Step 5] Rejects disjoint spans instead of silently collapsing semantics")
    public void rejectsDisjointTimeSpans() {
        final IllegalStateException exception = assertThrows(IllegalStateException.class,
            () -> new TimeSpanParser().parse("1944/1945,1949"));

        assertTrue(exception.getMessage().startsWith("Disjoint time spans are not supported:"));
    }

    @Test
    @DisplayName("[Guard] Returns empty output for null input on fail-safe API")
    public void returnsEmptyStringForNullInput() {
        assertEquals("", TimeParser.getInstance().parseTime(null));
    }

    @Test
    @DisplayName("[Guard] Rejects oversized inputs before expensive normalization and parsing")
    public void rejectsOversizedInputEarly() {
        final String input = "x".repeat(5000);

        final ParseResult result = TimeParser.getInstance().parseTimeResult(input);

        assertFalse(result.isSuccessful());
        assertEquals("", result.getOutput());
        assertEquals("INPUT_TOO_LONG", result.getErrorType());
    }

    @Test
    @DisplayName("[Step 5] Returns empty output for unsupported disjoint expressions")
    public void returnsEmptyStringForUnsupportedDisjointInput() {
        assertEquals("", TimeParser.getInstance().parseTime("1944-1945/1949", "solr-doc-42"));
    }

    @Test
    @DisplayName("[Diagnostics] Tracks aggregated parser errors with contextual metadata")
    public void exposesAggregatedErrorStats() {
        final TimeParser parser = TimeParser.getInstance();
        final Map<String, ParseErrorStats> beforeStats = parser.getErrorStats();
        final int before = beforeStats.containsKey("DISJOINT_TIME_SPAN")
            ? beforeStats.get("DISJOINT_TIME_SPAN").getCount()
            : 0;

        parser.parseTime("1944-1945/1949", "solr-doc-stats");

        final Map<String, ParseErrorStats> stats = parser.getErrorStats();
        final ParseErrorStats disjointStats = stats.get("DISJOINT_TIME_SPAN");

        assertTrue(disjointStats != null);
        assertEquals(before + 1, disjointStats.getCount());
        assertEquals("solr-doc-stats", disjointStats.getLastContext());
    }

    @Test
    @DisplayName("[Diagnostics] Resets aggregated parser error counters to a clean state")
    public void resetsAggregatedErrorStats() {
        final TimeParser parser = TimeParser.getInstance();
        parser.parseTime("1944-1945/1949", "solr-doc-reset");

        assertTrue(parser.getErrorStats().containsKey("DISJOINT_TIME_SPAN"));

        parser.resetErrorStats();

        assertTrue(parser.getErrorStats().isEmpty());
    }

    @Test
    @DisplayName("[Diagnostics] Captures parse error stats safely during concurrent parsing")
    public void capturesErrorStatsDuringConcurrentParsing() throws Exception {
        final TimeParser parser = TimeParser.getInstance();
        parser.resetErrorStats();

        final int taskCount = 24;
        final ExecutorService executor = Executors.newFixedThreadPool(6);
        try {
            final List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < taskCount; i++) {
                final int index = i;
                futures.add(executor.submit(() -> {
                    parser.parseTime("1944-1945/1949", "ctx-" + index);
                    final ParseErrorStats stats = parser.getErrorStats().get("DISJOINT_TIME_SPAN");
                    assertTrue(stats == null || stats.getCount() >= 1);
                    return null;
                }));
            }

            for (final Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }

        final ParseErrorStats stats = parser.getErrorStats().get("DISJOINT_TIME_SPAN");
        assertNotNull(stats);
        assertEquals(taskCount, stats.getCount());
        assertNotNull(stats.getFirstContext());
        assertNotNull(stats.getLastContext());
    }

    private static String expectedIndexRange(final TimeSpan timeSpan) {
        return timeSpan.getStartDate().getLong(JulianFields.JULIAN_DAY)
                + "|"
                + timeSpan.getEndDate().getLong(JulianFields.JULIAN_DAY);
    }
}

