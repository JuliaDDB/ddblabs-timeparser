/* 
 * Copyright 2012-2026 Deutsche Digitale Bibliothek
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.ddb.labs.timeparser;

import de.ddb.labs.timeparser.data.InputParser;
import de.ddb.labs.timeparser.data.Outputter;
import de.ddb.labs.timeparser.data.PatternParser;
import de.ddb.labs.timeparser.data.Token;
import de.ddb.labs.timeparser.facet.Facet;
import de.ddb.labs.timeparser.facet.FacetReader;
import de.ddb.labs.timeparser.internal.ParseErrorCounter;
import de.ddb.labs.timeparser.internal.RuleCandidate;
import de.ddb.labs.timeparser.model.FacetNotation;
import de.ddb.labs.timeparser.model.ParseErrorStats;
import de.ddb.labs.timeparser.model.ParseResult;
import de.ddb.labs.timeparser.replacement.Replacement;
import de.ddb.labs.timeparser.replacement.ReplacementReader;
import de.ddb.labs.timeparser.rule.Rule;
import de.ddb.labs.timeparser.rule.RuleReader;
import de.ddb.labs.timeparser.timespan.TimeSpan;
import de.ddb.labs.timeparser.timespan.TimeSpanParser;
import java.time.LocalDate;
import java.time.temporal.JulianFields;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.extern.slf4j.Slf4j;

/**
 * <p>
 * Converts a textual representation of a point or period in time into:
 * </p>
 * <ul>
 * <li>a value, that can be used to sort documents by (starting) date, and</li>
 * <li>a list of time periods in which it lies.</li>
 * </ul>
 * <p>
 * The process consists of four steps:
 * </p>
 * <ol>
 * <li>Rule selection</li>
 * <li>Input normalization</li>
 * <li>Time span calculation</li>
 * <li>Final output string generation</li>
 * </ol>
 */
@Slf4j
public final class TimeParser {

    /**
     * Output day-index strategy used by {@code parseTime(...)}.
     */
    public enum IndexDaysMode {
        /**
         * Use Julian Day Number via {@link JulianFields#JULIAN_DAY}.
         */
        JULIAN_DAY,
        /**
         * Use the legacy era/year-of-era indexing algorithm.
         */
        LEGACY
    }

    /** Classpath location of the rule knowledge base. */
    private static final String RULES_RESOURCE = "conf/timeparser/rules.csv";
    /** Classpath location of the facet mapping table. */
    private static final String FACETS_RESOURCE = "conf/timeparser/facets.csv";
    /** Classpath location of regex and literal normalization entries. */
    private static final String NORMALIZATIONS_RESOURCE = "conf/timeparser/normalizations.csv";
    /** Character encoding used for all bundled CSV resources. */
    private static final String CHARSET_NAME = "UTF-8";

    /** Fail-safe return value for parse methods that intentionally do not throw. */
    private static final String EMPTY_RESULT = "";
    /** Fixed UTC timezone used by the legacy day-index calculation path. */
    private static final TimeZone TIMEZONE = TimeZone.getTimeZone("UTC");
    /** Number of detailed warning logs emitted per error type before switching to summaries. */
    private static final int DETAILED_LOG_LIMIT_PER_ERROR = 1;
    /** Interval for aggregated warning summaries of repeated parse failures. */
    private static final int SUMMARY_LOG_EVERY_NTH_ERROR = 100;
    /** Upper guardrail for accepted input size to avoid excessive work and allocation churn. */
    private static final int MAX_INPUT_LENGTH = 2048;
    /** Maximum number of characters retained for logged or stored diagnostic values. */
    private static final int LOG_VALUE_MAX_LENGTH = 256;
    /** Enables debug stack traces for parse failures via the timeparser.logStacktraces system property. */
    private static final boolean LOG_STACKTRACES = Boolean.getBoolean("timeparser.logStacktraces");

    /**
     * Characters that must match literally inside rule masks. The dash list includes
     * both the ASCII hyphen and the en dash used by the data set.
     */
    private static final List<Character> AUXILIAR_CHARS = stringToCharList(",=?/()-–.[]0acuorcfABJMbehilnrvDVIZX");
    private static final List<Character> DYNAMIC_CHARS = stringToCharList("#");
    /** Input masks that must only match literally and are not generalized like normal rule masks. */
    private static final List<String> PERIOD_RULES = Arrays.asList(
            "Ottonisch",
            "Römisch",
            "Karolingisch",
            "Klassizistisch");

    /** Preloaded literal month replacements used during mask matching and transformation. */
    private static final List<Replacement> MONTH_REPLACEMENTS = loadConfiguredReplacements(
            NORMALIZATIONS_RESOURCE,
            false,
            "month");
    /** Preloaded regex weekday replacements used during mask matching and transformation. */
    private static final List<Replacement> WEEKDAY_REPLACEMENTS = loadConfiguredReplacements(
            NORMALIZATIONS_RESOURCE,
            true,
            "weekday");

    /** Shared stateless parser for translating rule masks and patterns into token streams. */
    private static final PatternParser PATTERN_PARSER = new PatternParser();
    /** Shared stateless parser for turning normalized expressions into concrete date spans. */
    private static final TimeSpanParser TIME_SPAN_PARSER = new TimeSpanParser();
    /** Process-wide error counters grouped by derived error type for diagnostics and monitoring. */
    private static final ConcurrentMap<String, ParseErrorCounter> PARSE_ERROR_COUNTERS = new ConcurrentHashMap<>();

    private final List<Rule> rules;
    private final List<Facet> facets;
    private final List<Replacement> inputNormalizations;
    private final Map<Integer, Map<Integer, List<RuleCandidate>>> ruleCandidatesByLength;
    private final Map<Rule, InputParser> inputParsersByRule;
    private final Map<Rule, Outputter> outputtersByRule;

    /**
     * Private constructor used by the holder singleton.
     */
    private TimeParser() {
        try {
            this.rules = Collections.unmodifiableList(RuleReader.read(RULES_RESOURCE, CHARSET_NAME));
            this.facets = Collections.unmodifiableList(FacetReader.read(FACETS_RESOURCE, CHARSET_NAME));
            this.inputNormalizations = Collections.unmodifiableList(
                    ReplacementReader.read(NORMALIZATIONS_RESOURCE, CHARSET_NAME, true, "normalization"));
            this.ruleCandidatesByLength = buildRuleIndex(this.rules);
            this.inputParsersByRule = buildInputParserIndex(this.rules);
            this.outputtersByRule = buildOutputterIndex(this.rules);
            PARSE_ERROR_COUNTERS.clear();
        } catch (Exception exception) {
            throw new RuntimeException(exception.getMessage(), exception);
        }
    }

    /**
     * Returns the shared parser instance.
     *
     * @return singleton parser instance
     */
    public static TimeParser getInstance() {
        return Holder.INSTANCE;
    }

    /**
     * Returns all loaded transformation rules.
     *
     * @return loaded rules
     */
    public List<Rule> getRules() {
        return rules;
    }

    /**
     * Returns a snapshot of aggregated parse error counters.
     *
     * @return immutable map keyed by error type
     */
    public Map<String, ParseErrorStats> getErrorStats() {
        final Map<String, ParseErrorStats> snapshot = new HashMap<>();
        for (final Map.Entry<String, ParseErrorCounter> entry : PARSE_ERROR_COUNTERS.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue().snapshot());
        }
        return Collections.unmodifiableMap(snapshot);
    }

    /**
     * Clears all aggregated parse error counters.
     */
    public void resetErrorStats() {
        PARSE_ERROR_COUNTERS.clear();
    }

    /**
     * Parses input using default settings (no context id, Julian Day output index).
     *
     * @param input textual date/time expression
     * @return parsed output or empty string on parse failure
     */
    public String parseTime(final String input) {
        return parseTime(input, null, IndexDaysMode.JULIAN_DAY);
    }

    /**
     * Parses input with an explicit day-index mode and no context id.
     *
     * @param input textual date/time expression
     * @param indexDaysMode output day-index strategy
     * @return parsed output or empty string on parse failure
     */
    public String parseTime(final String input, final IndexDaysMode indexDaysMode) {
        return parseTime(input, null, indexDaysMode);
    }

    /**
     * Parses input with optional context id using Julian Day output index.
     *
     * @param input textual date/time expression
     * @param contextId optional correlation id for logging/monitoring
     * @return parsed output or empty string on parse failure
     */
    public String parseTime(final String input, final String contextId) {
        return parseTime(input, contextId, IndexDaysMode.JULIAN_DAY);
    }

    /**
     * Parses input with optional context id and explicit day-index mode.
     *
     * @param input textual date/time expression
     * @param contextId optional correlation id for logging/monitoring
     * @param indexDaysMode output day-index strategy
     * @return parsed output or empty string on parse failure
     */
    public String parseTime(final String input, final String contextId, final IndexDaysMode indexDaysMode) {
        return parseTimeResult(input, contextId, indexDaysMode).getOutput();
    }

    /**
     * Parses input and returns the full structured result instead of only the
     * legacy output string.
     *
     * @param input textual date/time expression
     * @return structured parse result that can be serialized as JSON
     */
    public ParseResult parseTimeResult(final String input) {
        return parseTimeResult(input, null, IndexDaysMode.JULIAN_DAY);
    }

    /**
     * Parses input into a structured result with an explicit day-index mode.
     */
    public ParseResult parseTimeResult(final String input, final IndexDaysMode indexDaysMode) {
        return parseTimeResult(input, null, indexDaysMode);
    }

    /**
     * Parses input into a structured result with an optional context id.
     */
    public ParseResult parseTimeResult(final String input, final String contextId) {
        return parseTimeResult(input, contextId, IndexDaysMode.JULIAN_DAY);
    }

    /**
     * Parses input into a structured result with optional context id and explicit
     * day-index mode.
     */
    public ParseResult parseTimeResult(final String input, final String contextId,
            final IndexDaysMode indexDaysMode) {
        final IndexDaysMode mode = indexDaysMode == null ? IndexDaysMode.JULIAN_DAY : indexDaysMode;
        if (input == null || input.isEmpty()) {
            return ParseResult.failure(input, contextId, mode, EMPTY_RESULT, Collections.emptyList(), null,
                    EMPTY_RESULT, null, Collections.emptyList(), EMPTY_RESULT, null, null, EMPTY_RESULT,
                    "EMPTY_INPUT", "Input must not be null or empty");
        }
        if (input.length() > MAX_INPUT_LENGTH) {
            final String message = "Input length exceeds maximum supported length of " + MAX_INPUT_LENGTH
                    + " characters";
            logParseFailure(contextId, abbreviateForLog(input), message, null);
            return ParseResult.failure(input, contextId, mode, EMPTY_RESULT, Collections.emptyList(), null,
                    EMPTY_RESULT, null, Collections.emptyList(), EMPTY_RESULT, null, null, EMPTY_RESULT,
                    "INPUT_TOO_LONG", message);
        }

        try {
            return doParseTimeResult(input, contextId, mode);
        } catch (Exception exception) {
            final String message = exception.getMessage();
            logParseFailure(contextId, input, message, exception);
            return ParseResult.failure(input, contextId, mode, normalizeInput(input), Collections.emptyList(), null,
                    EMPTY_RESULT, null, Collections.emptyList(), EMPTY_RESULT, null, null, EMPTY_RESULT,
                    classifyErrorType(message, exception), message);
        }
    }

    /**
     * Executes the full transformation pipeline for one input string and exposes
     * intermediate state for inspection.
     */
    private ParseResult doParseTimeResult(final String input, final String contextId, final IndexDaysMode indexDaysMode)
            throws Exception {
        final String preprocessedInput = applyNormalizationRules(input);
        final String normalizedInput = tokenizeMonthsAndWeekdays(preprocessedInput);
        final List<Rule> matchingRules = findMatchingRules(normalizedInput);
        if (matchingRules.size() > 1) {
            final String message = "Multiple rules found for input string \"" + input + "\"";
            logParseFailure(contextId, input, message, null);
            return ParseResult.failure(input, contextId, indexDaysMode, normalizedInput, matchingRules, null,
                    EMPTY_RESULT, null, Collections.emptyList(), EMPTY_RESULT, null, null, EMPTY_RESULT,
                    "MULTIPLE_RULES", message);
        }

        final Rule matchedRule = matchingRules.isEmpty() ? null : matchingRules.get(0);
        final String transformedInput = matchedRule == null ? preprocessedInput : applyRule(preprocessedInput, matchedRule);
        final TimeSpan timeSpan = TIME_SPAN_PARSER.parse(transformedInput);
        return buildParseResult(input, contextId, indexDaysMode, normalizedInput, matchingRules, matchedRule,
                transformedInput, timeSpan);
    }

    private ParseResult buildParseResult(final String input, final String contextId, final IndexDaysMode indexDaysMode,
            final String normalizedInput, final List<Rule> matchingRules, final Rule matchedRule,
            final String transformedInput, final TimeSpan timeSpan) {
        final List<FacetNotation> facetNotations = resolveFacetNotations(timeSpan);
        final String facetString = buildFacetString(facetNotations);
        final long startDays = computeIndexDay(timeSpan.getStartDate(), indexDaysMode);
        final long endDays = computeIndexDay(timeSpan.getEndDate(), indexDaysMode);
        final String output = facetString + " " + startDays + "|" + endDays;

        return ParseResult.success(input, contextId, indexDaysMode, normalizedInput, matchingRules, matchedRule,
                transformedInput, timeSpan, facetNotations, facetString, startDays, endDays, output);
    }

    /**
     * Applies a selected rule to the raw input and produces the normalized parser
     * expression (step 4 of the pipeline).
     *
     * @param input raw or pre-normalized input string
     * @param rule  the rule to apply
     * @return transformed input ready for {@link de.ddb.labs.timeparser.timespan.TimeSpanParser}
     */
    public String applyRule(final String input, final Rule rule) throws Exception {
        final InputParser inputParser = inputParsersByRule.get(rule);
        final Outputter outputter = outputtersByRule.get(rule);
        if (inputParser == null || outputter == null) {
            throw new IllegalStateException("No compiled parser state found for rule: " + rule);
        }
        return outputter.createOutputString(inputParser.parseInputString(input));
    }

    /**
     * Finds all rules whose input mask matches the given tokenized input (step 3 of the pipeline).
     * The input must already have month/weekday names replaced by their tokens (see
     * {@link #tokenizeMonthsAndWeekdays(String)}).
     *
     * @param tokenizedInput normalized input with month/weekday tokens
     * @return matching rules; empty list if none match, more than one indicates an ambiguity
     */
    public List<Rule> findMatchingRules(final String tokenizedInput) {
        final List<RuleCandidate> foundRules = cleanupRules(getRuleCandidates(tokenizedInput));
        final List<Rule> matchingRules = new ArrayList<>(foundRules.size());
        for (final RuleCandidate candidate : foundRules) {
            matchingRules.add(candidate.getRule());
        }
        return matchingRules;
    }

    /**
     * Runs both normalization steps in sequence (convenience for internal use).
     */
    private String normalizeInput(final String input) {
        return tokenizeMonthsAndWeekdays(applyNormalizationRules(input));
    }

    /**
     * Replaces month and weekday names with cheap matching tokens (step 2 of the pipeline).
     * Call this after {@link #applyNormalizationRules(String)}.
     *
     * @param preprocessedInput output of {@link #applyNormalizationRules(String)}
     * @return input with month names replaced by {@code MM} and weekday names by {@code GG}
     */
    public String tokenizeMonthsAndWeekdays(final String preprocessedInput) {
        String result = preprocessedInput;
        result = Replacement.replaceAllWith(result, MONTH_REPLACEMENTS, "MM");
        result = Replacement.replaceAllWith(result, WEEKDAY_REPLACEMENTS, "GG");
        return result;
    }

    /**
     * Applies all entries from {@code normalizations.csv} to the raw input (step 1 of the pipeline).
     * This expands abbreviations, canonical spelling variants, and century/millennium expressions
     * before rule matching.
     *
     * @param input raw input string
     * @return pre-normalized input ready for {@link #tokenizeMonthsAndWeekdays(String)}
     */
    public String applyNormalizationRules(final String input) {
        return Replacement.applyAll(input, inputNormalizations);
    }

    private List<RuleCandidate> getRuleCandidates(final String input) {
        final Map<Integer, List<RuleCandidate>> candidatesByCompactLength = ruleCandidatesByLength.get(input.length());
        if (candidatesByCompactLength == null) {
            return Collections.emptyList();
        }

        final List<RuleCandidate> candidates = candidatesByCompactLength.get(countNonWhitespace(input));
        if (candidates == null) {
            return Collections.emptyList();
        }

        final List<RuleCandidate> foundRules = new ArrayList<>();
        for (final RuleCandidate candidate : candidates) {
            if (candidate.isPeriodLiteral() && !input.equals(candidate.getInputMask())) {
                continue;
            }
            if (matchesRuleMask(candidate.getInputMask(), input)) {
                foundRules.add(candidate);
            }
        }

        return foundRules;
    }

    private boolean matchesRuleMask(final String inputMask, final String input) {
        for (int index = 0; index < inputMask.length(); index++) {
            final char maskChar = inputMask.charAt(index);
            final char inputChar = input.charAt(index);
            if (!isMatching(maskChar, inputChar)) {
                return false;
            }
        }
        return true;
    }

    private boolean isMatching(final char maskChar, final char inputChar) {
        return !((maskChar == '#' && !Character.isDigit(inputChar))
                || (Character.isSpaceChar(maskChar) && !Character.isSpaceChar(inputChar))
                || (!Character.isSpaceChar(maskChar) && Character.isSpaceChar(inputChar))
                || (AUXILIAR_CHARS.contains(maskChar) && maskChar != inputChar)
                || (DYNAMIC_CHARS.contains(maskChar) && maskChar == inputChar));
    }

    /**
     * Reduces rule candidates to the most specific non-duplicate set.
     */
    private List<RuleCandidate> cleanupRules(final List<RuleCandidate> foundRules) {
        if (foundRules.size() > 1) {
            final int smallestNumberOfHashes = getSmallestNumberOfHashes(foundRules);
            final Iterator<RuleCandidate> iterator = foundRules.iterator();
            while (iterator.hasNext()) {
                if (iterator.next().getHashCount() > smallestNumberOfHashes) {
                    iterator.remove();
                }
            }
        }

        final Map<Rule, RuleCandidate> uniqueRules = new LinkedHashMap<>();
        for (final RuleCandidate candidate : foundRules) {
            uniqueRules.putIfAbsent(candidate.getRule(), candidate);
        }
        return new ArrayList<>(uniqueRules.values());
    }

    private int getSmallestNumberOfHashes(final List<RuleCandidate> foundRules) {
        int smallestNumberOfHashes = -1;
        for (final RuleCandidate candidate : foundRules) {
            if (smallestNumberOfHashes == -1 || candidate.getHashCount() < smallestNumberOfHashes) {
                smallestNumberOfHashes = candidate.getHashCount();
            }
        }
        return smallestNumberOfHashes;
    }

    /**
     * Looks up all DDB Zeitvokabular facets that overlap with the given time span (step 5 of the pipeline).
     *
     * @param timeSpan the parsed time span
     * @return matching facet notations in chronological order
     */
    public List<FacetNotation> resolveFacetNotations(final TimeSpan timeSpan) {
        final Map<String, FacetNotation> facetTokens = new LinkedHashMap<>();
        final int startYear = toFacetYear(timeSpan.getStartDate());
        final int endYear = toFacetYear(timeSpan.getEndDate());

        for (final Facet facet : facets) {
            if ((startYear <= facet.getLatestDate()) && (endYear >= facet.getEarliestDate())) {
                facetTokens.putIfAbsent(facet.getNotation(), new FacetNotation(
                        facet.getNotation(),
                        facet.getPrefLabelDe(),
                        facet.getPrefLabelEn()));
            }
        }

        return new ArrayList<>(facetTokens.values());
    }

    /**
     * Builds the pipe-separated facet id string from a list of notations (step 5b of the pipeline).
     *
     * @param facetNotations result of {@link #resolveFacetNotations(TimeSpan)}
     * @return e.g. {@code "time_62100|time_62110"}, or empty string if the list is empty
     */
    public String buildFacetString(final List<FacetNotation> facetNotations) {
        if (facetNotations.isEmpty()) {
            return EMPTY_RESULT;
        }

        final StringBuilder facetString = new StringBuilder();
        for (final FacetNotation facetNotation : facetNotations) {
            if (facetString.length() > 0) {
                facetString.append('|');
            }
            facetString.append(facetNotation.getNotation());
        }
        return facetString.toString();
    }

    /**
     * Converts a calendar date to a sortable index day number (step 6 of the pipeline).
     *
     * @param date         the start or end date of a time span
     * @param indexDaysMode {@link IndexDaysMode#JULIAN_DAY} (default) or {@link IndexDaysMode#LEGACY}
     * @return sortable day number
     */
    public long computeIndexDay(final LocalDate date, final IndexDaysMode indexDaysMode) {
        if (indexDaysMode == IndexDaysMode.LEGACY) {
            return getDateAsIndexDays(date);
        }
        return getJulianDayAsIndexDays(date);
    }

    private int toFacetYear(final LocalDate date) {
        final int year = date.getYear();
        return year > 0 ? year : year - 1;
    }

    private long getJulianDayAsIndexDays(final LocalDate date) {
        return date.getLong(JulianFields.JULIAN_DAY);
    }

    /**
     * @deprecated Kept for compatibility and reference only. Uses the historical
     *             era/year-of-era indexing model.
     */
    @Deprecated(since = "2.0.0", forRemoval = false)
    private long getDateAsIndexDays(final LocalDate date) {
        final Calendar calendar = new GregorianCalendar(TIMEZONE);
        calendar.clear();
        calendar.setLenient(false);

        final int prolepticYear = date.getYear();
        final boolean isAnnoDomini = prolepticYear > 0;
        final int yearOfEra = isAnnoDomini ? prolepticYear : (1 - prolepticYear);

        calendar.set(Calendar.ERA, isAnnoDomini ? GregorianCalendar.AD : GregorianCalendar.BC);
        calendar.set(Calendar.YEAR, yearOfEra);
        calendar.set(Calendar.MONTH, date.getMonthValue() - 1);
        calendar.set(Calendar.DAY_OF_MONTH, date.getDayOfMonth());

        long days = calendar.getTimeInMillis() / 86400000L;
        days += 719164;
        return days;
    }

    private void logParseFailure(final String contextId, final String input, final String message,
            final Exception exception) {
        final String errorType = classifyErrorType(message, exception);
        final ParseErrorCounter counter = PARSE_ERROR_COUNTERS.computeIfAbsent(errorType,
                ignored -> new ParseErrorCounter());
        final int count = counter.incrementAndTrack(contextId, input, message);

        final boolean shouldLogDetailed = count <= DETAILED_LOG_LIMIT_PER_ERROR;
        final boolean shouldLogSummary = count % SUMMARY_LOG_EVERY_NTH_ERROR == 0;

        if (shouldLogDetailed) {
            logDetailedFailure(contextId, input, message, errorType, count);
        } else if (shouldLogSummary) {
            log.warn(
                    "TimeParser error summary (type={}, count={}): firstContext=[{}], firstInput=[{}], lastContext=[{}], lastInput=[{}], lastMessage=[{}]",
                    errorType,
                    count,
                    counter.getFirstContext(),
                    counter.getFirstInput(),
                    counter.getLastContext(),
                    counter.getLastInput(),
                    counter.getLastMessage());
        }

        if (exception != null && LOG_STACKTRACES && log.isDebugEnabled() && (shouldLogDetailed || shouldLogSummary)) {
            log.debug("TimeParser parse failure details", exception);
        }
    }

    private void logDetailedFailure(final String contextId, final String input, final String message,
            final String errorType, final int count) {
        final String safeContextId = abbreviateForLog(contextId);
        final String safeInput = abbreviateForLog(input);
        if (safeContextId == null || safeContextId.isEmpty()) {
            log.warn("TimeParser failed (type={}, count={}) for input [{}]: {}", errorType, count, safeInput, message);
            return;
        }

        log.warn("TimeParser failed (type={}, count={}) for context [{}], input [{}]: {}",
                errorType,
                count,
                safeContextId,
                safeInput,
                message);
    }

    private static String abbreviateForLog(final String value) {
        if (value == null || value.length() <= LOG_VALUE_MAX_LENGTH) {
            return value;
        }
        return value.substring(0, LOG_VALUE_MAX_LENGTH) + "...";
    }

    private String classifyErrorType(final String message, final Exception exception) {
        if (message != null) {
            if (message.startsWith("Disjoint time spans are not supported:")) {
                return "DISJOINT_TIME_SPAN";
            }
            if (message.startsWith("Multiple rules found for input string")) {
                return "MULTIPLE_RULES";
            }
            if (message.startsWith("Input length exceeds maximum supported length of")) {
                return "INPUT_TOO_LONG";
            }
            if (message.contains("could not be parsed")) {
                return "INVALID_TIME_EXPRESSION";
            }
        }

        if (exception == null) {
            return "UNKNOWN";
        }
        return exception.getClass().getSimpleName();
    }

    private static Map<Integer, Map<Integer, List<RuleCandidate>>> buildRuleIndex(final List<Rule> loadedRules) {
        final Map<Integer, Map<Integer, List<RuleCandidate>>> index = new HashMap<>();
        for (final Rule rule : loadedRules) {
            final RuleCandidate candidate = new RuleCandidate(rule, PERIOD_RULES);
            index.computeIfAbsent(candidate.getInputMask().length(), ignored -> new HashMap<>())
                    .computeIfAbsent(candidate.getCompactLength(), ignored -> new ArrayList<>())
                    .add(candidate);
        }
        return index;
    }

    private static Map<Rule, InputParser> buildInputParserIndex(final List<Rule> loadedRules) {
        final Map<Rule, InputParser> index = new HashMap<>();
        for (final Rule rule : loadedRules) {
            final List<Token> inputPattern = PATTERN_PARSER.parse(rule.getInputMask(), rule.getInputPattern());
            index.put(rule, new InputParser(inputPattern, MONTH_REPLACEMENTS, WEEKDAY_REPLACEMENTS));
        }
        return Collections.unmodifiableMap(index);
    }

    private static Map<Rule, Outputter> buildOutputterIndex(final List<Rule> loadedRules) {
        final Map<Rule, Outputter> index = new HashMap<>();
        for (final Rule rule : loadedRules) {
            final List<Token> outputPattern = PATTERN_PARSER.parse(true, rule.getOutputMask(), rule.getOutputPattern());
            index.put(rule, new Outputter(outputPattern));
        }
        return Collections.unmodifiableMap(index);
    }

    private static List<Replacement> loadConfiguredReplacements(final String resourcePath, final boolean regex,
            final String category) {
        try {
            return Collections.unmodifiableList(ReplacementReader.read(resourcePath, CHARSET_NAME, regex, category));
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static int countNonWhitespace(final String value) {
        int count = 0;
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isWhitespace(value.charAt(index))) {
                count++;
            }
        }
        return count;
    }

    private static List<Character> stringToCharList(final String input) {
        final List<Character> result = new ArrayList<>();
        for (final char character : input.toCharArray()) {
            result.add(character);
        }
        return result;
    }

    private static final class Holder {
        private static final TimeParser INSTANCE = new TimeParser();
    }

}
