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
package de.ddb.labs.timeparser.facet;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import lombok.extern.slf4j.Slf4j;

/**
 * <p>
 * Reads facets from a CSV file using named column headers.
 * </p>
 * <ul>
 * <li>The first line must be a header row with the following column names
 * (order is irrelevant): {@code id}, {@code notation}, {@code earliestDate},
 * {@code latestDate}, {@code prefLabelDe}, {@code prefLabelEn},
 * {@code sortOrder}.</li>
 * <li>The delimiter is {@code ;} and the quote character is {@code "}.</li>
 * </ul>
 */
@Slf4j
public final class FacetReader {

    private FacetReader() {
    }

    /**
     * Reads all facets from the configured classpath resource.
     *
     * @param path classpath-relative resource path
     * @param charsetName character set used to decode the resource
     * @return loaded facets, excluding malformed numeric rows
     */
    public static List<Facet> read(final String path, final String charsetName) throws IOException, ParseException {
        final List<Facet> facets = new ArrayList<>();
        final CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .get();

        try (final InputStream in = FacetReader.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("Facet file does could not be found for the given path \"" + path + "\"");
            }
            try (final CSVParser parser = CSVParser.parse(new InputStreamReader(in, charsetName), format)) {
                for (final String required : new String[]{"id", "notation", "earliestDate", "latestDate",
                        "prefLabelDe", "prefLabelEn", "sortOrder"}) {
                    if (!parser.getHeaderMap().containsKey(required)) {
                        throw new ParseException(
                                "Required column \"" + required + "\" not found in facet file \"" + path + "\"", 0);
                    }
                }

                final Set<String> seenIds = new HashSet<>();
                for (final CSVRecord record : parser) {
                    final int lineNumber = Math.toIntExact(record.getRecordNumber());
                    final String id = record.get("id");
                    if (!seenIds.add(id)) {
                        throw new ParseException(
                                "Duplicate id \"" + id + "\" in facet file \"" + path + "\"",
                                lineNumber);
                    }
                    try {
                        facets.add(new Facet(
                                record.get("id"),
                                record.get("notation"),
                                Long.valueOf(record.get("earliestDate")),
                                Long.valueOf(record.get("latestDate")),
                                record.get("prefLabelDe"),
                                record.get("prefLabelEn"),
                                Integer.valueOf(record.get("sortOrder"))));
                    } catch (NumberFormatException exception) {
                        log.warn("Skipping facet row with invalid numeric value in facet file \"{}\", line {}: {} ({})",
                                path,
                                lineNumber,
                                record,
                                exception.getMessage());
                    }
                }
            }
        }

        return facets;
    }
}
