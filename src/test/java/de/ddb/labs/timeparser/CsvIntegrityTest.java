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

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structural integrity tests for all CSV configuration files.
 *
 * <p>Each CSV file is checked as a separate parameterized test run so that
 * the file name appears directly in the test report and failures can be
 * attributed to the correct file at a glance.</p>
 */
public class CsvIntegrityTest {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "conf/timeparser/normalizations.csv",
        "conf/timeparser/rules.csv",
        "conf/timeparser/tests.csv",
        "conf/timeparser/facets.csv"
    })
    @DisplayName("[CSV] Eindeutige IDs in allen Konfigurationsdateien")
    public void csvFilesHaveUniqueIds(final String resourcePath) throws Exception {
        final CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .get();
        final Set<String> seen = new HashSet<>();
        final List<String> duplicates = new ArrayList<>();
        try (final InputStream in = CsvIntegrityTest.class.getClassLoader().getResourceAsStream(resourcePath);
             final CSVParser parser = CSVParser.parse(new InputStreamReader(in, StandardCharsets.UTF_8), format)) {
            for (final CSVRecord record : parser) {
                final String id = record.get("id");
                if (!seen.add(id)) {
                    duplicates.add(id + " (Zeile " + record.getRecordNumber() + ")");
                }
            }
        }
        assertTrue(duplicates.isEmpty(), "Doppelte IDs gefunden: " + duplicates);
    }
}
