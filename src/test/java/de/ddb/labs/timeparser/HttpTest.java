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

import com.fasterxml.jackson.databind.ObjectMapper;
import de.ddb.labs.timeparser.http.HttpParseResponse;
import de.ddb.labs.timeparser.model.ParseResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests für die HTTP-Antwortstruktur und JSON-Serialisierung des
 * {@link TimeParserHttpServer}.
 *
 * <p>Geprüft wird, dass Feldnamen und optionale Felder gemäß API-Vertrag
 * korrekt gesetzt oder weggelassen werden.</p>
 */
public class HttpTest {

    @Test
    @DisplayName("[HTTP] Erfolgreiche Antwort enthält ISO-Feldnamen statt Java-Property-Namen")
    public void usesExplicitIsoFieldNamesInHttpJson() throws Exception {
        final ParseResult result = TimeParser.getInstance().parseTimeResult("Mai 2010");
        final ObjectMapper objectMapper = TimeParserHttpServer.createObjectMapper();

        final String json = objectMapper.writeValueAsString(HttpParseResponse.from(result));

        assertTrue(json.contains("\"startISODate\":\"2010-05-01\""));
        assertTrue(json.contains("\"endISODate\":\"2010-05-31\""));
        assertFalse(json.contains("\"startDate\""));
        assertFalse(json.contains("\"endDate\""));
    }

    @Test
    @DisplayName("[HTTP] Fehlerhafte Antwort lässt leere Metadatenfelder weg")
    public void omitsEmptyFieldsFromHttpFailureJson() throws Exception {
        final ParseResult result = TimeParser.getInstance().parseTimeResult("200 V.Vh");
        final ObjectMapper objectMapper = TimeParserHttpServer.createObjectMapper();

        final String json = objectMapper.writeValueAsString(HttpParseResponse.from(result));

        assertFalse(json.contains("\"matchingRules\""));
        assertFalse(json.contains("\"tokenized\""));
        assertFalse(json.contains("\"facetNotations\""));
        assertFalse(json.contains("\"facetString\""));
        assertFalse(json.contains("\"output\""));
        assertTrue(json.contains("\"errorType\":\"INVALID_TIME_EXPRESSION\""));
    }
}
