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
import de.ddb.labs.timeparser.data.TokenWithValue;
import de.ddb.labs.timeparser.model.ParseResult;
import de.ddb.labs.timeparser.replacement.Replacement;
import de.ddb.labs.timeparser.replacement.ReplacementReader;
import de.ddb.labs.timeparser.rule.Rule;
import de.ddb.labs.timeparser.rule.RuleReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CSV-driven regression tests for the rules.csv / tests.csv contract.
 *
 * <p>Each test case row in {@code tests.csv} becomes an individual JUnit 5
 * {@link DynamicTest}, so failures are immediately visible by test ID in the
 * test report — no loop-scanning required.</p>
 *
 * <ul>
 *   <li>{@link #tokenizationTests()} — Steps 1-2: normalization + month/weekday
 *       tokenization</li>
 *   <li>{@link #outputTests()} — Steps 1-4: rule mask application and output
 *       string generation</li>
 *   <li>{@link #timespanTests()} — Steps 1-6: full pipeline including facet
 *       look-up and time-span computation</li>
 * </ul>
 */
public class RulesTest {

    private static final String RULES_RESOURCE = "conf/timeparser/rules.csv";
    private static final String CHARSET = StandardCharsets.UTF_8.name();

    // -------------------------------------------------------------------------
    // Regelabdeckung
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("[Abdeckung] Jede Regel in rules.csv besitzt mindestens einen Test")
    public void allRulesHaveTests() throws Exception {
        final List<Rule> rules = RuleReader.read(RULES_RESOURCE, CHARSET, true);

        // Tabellarische Ausgabe -----------------------------------------------
        final String fmt = "%-8s %-52s %7s%n";
        System.out.printf("%n");
        System.out.printf(fmt, "Regel", "Eingabemaske", "#Tests");
        System.out.println("-".repeat(70));

        final List<String> missing = new ArrayList<>();
        for (final Rule rule : rules) {
            final int count = rule.getTests().size();
            if (count == 0) {
                missing.add(rule.getId());
            }
            final String marker = count == 0 ? " ←" : "";
            System.out.printf(fmt,
                    rule.getId(),
                    truncate(rule.getInputMask(), 52),
                    count + marker);
        }
        System.out.println("-".repeat(70));
        System.out.printf("%d Regeln gesamt  |  %d mit Tests  |  %d ohne Tests%n%n",
                rules.size(), rules.size() - missing.size(), missing.size());
        // ---------------------------------------------------------------------

        assertTrue(missing.isEmpty(), "Regeln ohne Tests: " + missing);
    }

    // -------------------------------------------------------------------------
    // Steps 1–2: normalization + tokenization
    // -------------------------------------------------------------------------

    @TestFactory
    @DisplayName("[Steps 1-2] Tokenisierung für alle tests.csv-Einträge")
    Stream<DynamicTest> tokenizationTests() throws Exception {
        final List<Rule> rules = RuleReader.read(RULES_RESOURCE, CHARSET, true);
        return rules.stream()
                .flatMap(rule -> rule.getTests().stream()
                        .filter(test -> test.getTokenized() != null && !test.getTokenized().isEmpty())
                        .map(test -> DynamicTest.dynamicTest(
                                testLabel(test.getId(), rule.getId(), test.getInput()),
                                () -> {
                                    final String normalized = TimeParser.getInstance()
                                            .applyNormalizationRules(test.getInput());
                                    final String actual = TimeParser.getInstance()
                                            .tokenizeMonthsAndWeekdays(normalized);
                                    assertEquals(test.getTokenized(), actual,
                                            testLabel(test.getId(), rule.getId(), test.getInput())
                                            + actual);
                                })));
    }

    // -------------------------------------------------------------------------
    // Steps 1–4: rule mask application and output generation
    // -------------------------------------------------------------------------

    @TestFactory
    @DisplayName("[Steps 1-4] Ausgabe-Transformation für alle tests.csv-Einträge")
    Stream<DynamicTest> outputTests() throws Exception {
        final List<Rule> rules = RuleReader.read(RULES_RESOURCE, CHARSET, true);
        final PatternParser patternParser = new PatternParser();
        final List<Replacement> months = monthReplacements();
        final List<Replacement> weekdays = weekdayReplacements();
        return rules.stream()
                .flatMap(rule -> rule.getTests().stream()
                        .filter(test -> test.getOutput() != null && !test.getOutput().isEmpty())
                        .map(test -> DynamicTest.dynamicTest(
                                testLabel(test.getId(), rule.getId(), test.getInput()),
                                () -> {
                                    final List<Token> inputPattern = patternParser.parse(
                                            rule.getInputMask(), rule.getInputPattern());
                                    final InputParser inputParser = new InputParser(
                                            inputPattern, months, weekdays);
                                    final String normalized = TimeParser.getInstance()
                                            .applyNormalizationRules(test.getInput());
                                    final List<TokenWithValue> tokens;
                                    try {
                                        tokens = inputParser.parseInputString(normalized);
                                    } catch (final IllegalStateException e) {
                                        fail(testLabel(test.getId(), rule.getId(), test.getInput())
                                                + " – normalisierte Eingabe «" + normalized
                                                + "» passt nicht auf Eingabemuster «"
                                                + rule.getInputPattern() + "»");
                                        return;
                                    }
                                    final List<Token> outputPattern = patternParser.parse(
                                            true, rule.getOutputMask(), rule.getOutputPattern());
                                    final String actual = new Outputter(outputPattern).createOutputString(tokens);
                                    assertEquals(test.getOutput(), actual,
                                            testLabel(test.getId(), rule.getId(), 
                                            test.getInput()) 
                                            + (actual == null ? "<null>" : actual));
                                })));
    }

    // -------------------------------------------------------------------------
    // Steps 1–6: full pipeline
    // -------------------------------------------------------------------------

    @TestFactory
    @DisplayName("[Steps 1-6] Zeitspanne für alle tests.csv-Einträge")
    Stream<DynamicTest> timespanTests() throws Exception {
        final List<Rule> rules = RuleReader.read(RULES_RESOURCE, CHARSET, true);
        return rules.stream()
                .flatMap(rule -> rule.getTests().stream()
                        .filter(test -> test.getTimespan() != null && !test.getTimespan().isEmpty())
                        .map(test -> DynamicTest.dynamicTest(
                                testLabel(test.getId(), rule.getId(), test.getInput()),
                                () -> {
                                    final ParseResult result = TimeParser.getInstance()
                                            .parseTimeResult(test.getInput());
                                    assertTrue(result.isSuccessful(),
                                            testLabel(test.getId(), rule.getId(), test.getInput())
                                            + " – Parsing fehlgeschlagen: " + result.getErrorType()
                                            + " – " + result.getErrorMessage());
                                    final String[] parts = test.getTimespan().split("/", 2);
                                    final LocalDate expectedStart = LocalDate.parse(parts[0]);
                                    final LocalDate expectedEnd = parts.length == 2
                                            ? LocalDate.parse(parts[1]) : expectedStart;
                                    assertEquals(expectedStart, result.getTimeSpan().getStartDate(),
                                            testLabel(test.getId(), rule.getId(), test.getInput()) + " Startdatum weicht ab");
                                    assertEquals(expectedEnd, result.getTimeSpan().getEndDate(),
                                            testLabel(test.getId(), rule.getId(), test.getInput()) + " Enddatum weicht ab");
                                })));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the human-readable label shown for each dynamic test in the
     * report, e.g. {@code T38 [R38] «1963 (?)»}.
     */
    private static String testLabel(final String testId, final String ruleId, final String input) {
        return testId + " [" + ruleId + "] «" + input + "»";
    }

    private static List<Replacement> monthReplacements() throws Exception {
        return ReplacementReader.read("conf/timeparser/normalizations.csv", CHARSET, false, "month");
    }

    private static List<Replacement> weekdayReplacements() throws Exception {
        return ReplacementReader.read("conf/timeparser/normalizations.csv", CHARSET, false, "weekday");
    }

    private static String truncate(final String s, final int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
