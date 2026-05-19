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
package de.ddb.labs.timeparser.http;

import de.ddb.labs.timeparser.TimeParser.IndexDaysMode;
import de.ddb.labs.timeparser.model.FacetNotation;
import de.ddb.labs.timeparser.model.ParseResult;
import de.ddb.labs.timeparser.rule.Rule;
import java.util.List;

/**
 * JSON-facing parse response for the embedded HTTP server.
 *
 * <p>The first six fields mirror the processing pipeline steps visible in
 * {@code tests.csv}: {@code input} → {@code normalized} → {@code tokenized}
 * → {@code output} + {@code timespan}. Technical details follow.</p>
 */
public record HttpParseResponse(
        boolean successful,
        // --- Processing pipeline steps ---
        String input,
        String normalized,
        String tokenized,
        String output,
        HttpTimeSpan timespan,
        // --- Technical details ---
        IndexDaysMode indexDaysMode,
        List<Rule> matchingRules,
        Rule matchedRule,
        List<FacetNotation> facetNotations,
        String facetString,
        Long startIndexDay,
        Long endIndexDay,
        String errorType,
        String errorMessage) {

    public static HttpParseResponse from(final ParseResult result) {
        return new HttpParseResponse(
                result.isSuccessful(),
                result.getInput(),
                result.getNormalizedInput(),
                result.getTransformedInput(),
                result.getOutput(),
                HttpTimeSpan.from(result.getTimeSpan()),
                result.getIndexDaysMode(),
                result.getMatchingRules(),
                result.getMatchedRule(),
                result.getFacetNotations(),
                result.getFacetString(),
                result.getStartIndexDay(),
                result.getEndIndexDay(),
                result.getErrorType(),
                result.getErrorMessage());
    }
}
