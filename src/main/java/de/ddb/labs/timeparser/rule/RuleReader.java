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
package de.ddb.labs.timeparser.rule;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/**
 * <p>
 * Reads transformation rules from a CSV file using named column headers.
 * </p>
 * <ul>
 * <li>The first line must be a header row with the following column names
 * (order is irrelevant): {@code id}, {@code inputMask}, {@code inputPattern},
 * {@code outputMask}, {@code outputPattern}.</li>
 * <li>The delimiter is {@code ;} and the quote character is {@code "}.</li>
 * <li>Rules are de-duplicated by input mask; only the first occurrence is
 * kept.</li>
 * <li>Optionally, per-rule tests are loaded from a {@code tests.csv} file
 * located in the same directory as the rules file. Tests are disabled by
 * default and must be explicitly requested via the {@code loadTests}
 * parameter.</li>
 * </ul>
 */
public final class RuleReader {

    private RuleReader() {
    }

    /**
     * Reads all rules without loading associated tests.
     *
     * @param path        classpath-relative resource path to {@code rules.csv}
     * @param charsetName character set used to decode the resource
     * @return loaded rules, de-duplicated by input mask
     */
    public static List<Rule> read(final String path, final String charsetName) throws IOException, ParseException {
        return read(path, charsetName, false);
    }

    /**
     * Reads all rules from the configured classpath resource.
     *
     * @param path        classpath-relative resource path to {@code rules.csv}
     * @param charsetName character set used to decode the resource
     * @param loadTests   when {@code true}, per-rule tests are loaded from
     *                    {@code tests.csv} in the same directory; when
     *                    {@code false} (the default), each rule's test set is
     *                    empty
     * @return loaded rules, de-duplicated by input mask
     */
    public static List<Rule> read(final String path, final String charsetName, final boolean loadTests)
            throws IOException, ParseException {
        final CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .get();

        final Map<String, Set<Rule.Test>> testsByRuleId = loadTests
                ? readTests(path, charsetName, format)
                : Collections.emptyMap();

        final List<Rule> rules = new ArrayList<>();
        final Set<String> seenIds = new HashSet<>();
        final Set<String> inputMasks = new HashSet<>();

        try (final InputStream in = RuleReader.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("Rule file could not be found for the given path \"" + path + "\"");
            }
            try (final CSVParser parser = CSVParser.parse(new InputStreamReader(in, charsetName), format)) {
                for (final String required : new String[] { "id", "inputMask", "inputPattern", "outputMask",
                        "outputPattern" }) {
                    if (!parser.getHeaderMap().containsKey(required)) {
                        throw new ParseException(
                                "Required column \"" + required + "\" not found in rule file \"" + path + "\"", 0);
                    }
                }

                for (final CSVRecord record : parser) {
                    final String id = record.get("id");
                    if (!seenIds.add(id)) {
                        throw new ParseException(
                                "Duplicate id \"" + id + "\" in rule file \"" + path + "\"",
                                Math.toIntExact(record.getRecordNumber()));
                    }
                    final String inputMask = record.get("inputMask");
                    if (inputMasks.add(inputMask)) {
                        final Set<Rule.Test> tests = testsByRuleId.getOrDefault(id, Collections.emptySet());
                        rules.add(new Rule(id, inputMask, record.get("inputPattern"),
                                record.get("outputMask"), record.get("outputPattern"), tests));
                    }
                }
            }
        }
        return rules;
    }

    private static Map<String, Set<Rule.Test>> readTests(final String rulesPath, final String charsetName,
            final CSVFormat format) throws IOException, ParseException {
        final int lastSlash = rulesPath.lastIndexOf('/');
        final String testsPath = (lastSlash >= 0 ? rulesPath.substring(0, lastSlash + 1) : "") + "tests.csv";
        final Set<String> seenTestIds = new HashSet<>();

        final Map<String, Set<Rule.Test>> result = new HashMap<>();
        try (final InputStream in = RuleReader.class.getClassLoader().getResourceAsStream(testsPath)) {
            if (in == null) {
                throw new IOException("Tests file could not be found at \"" + testsPath + "\"");
            }
            try (final CSVParser parser = CSVParser.parse(new InputStreamReader(in, charsetName), format)) {
                for (final String required : new String[] { "id", "for", "input", "tokenized", "output", "timespan" }) {
                    if (!parser.getHeaderMap().containsKey(required)) {
                        throw new ParseException(
                                "Required column \"" + required + "\" not found in tests file \"" + testsPath + "\"",
                                0);
                    }
                }

                for (final CSVRecord record : parser) {
                    final String id = record.get("id");
                    if (!seenTestIds.add(id)) {
                        throw new ParseException("Duplicate id \"" + id + "\" in tests file \"" + testsPath + "\"",
                                Math.toIntExact(record.getRecordNumber()));
                    }
                    final Rule.Test test = new Rule.Test(
                            id,
                            record.get("input"),
                            record.get("normalized"),
                            record.get("tokenized"),
                            record.get("output"),
                            record.get("timespan"));
                    result.computeIfAbsent(record.get("for"), k -> new HashSet<>()).add(test);
                }
            }
        }
        return result;
    }
}
