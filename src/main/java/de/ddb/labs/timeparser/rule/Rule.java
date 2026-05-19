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

import lombok.EqualsAndHashCode;

import java.util.Set;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

/**
 * Transformation rule mapping one textual input pattern to a normalized output
 * pattern.
 */
@Getter
@EqualsAndHashCode
@AllArgsConstructor
@ToString
public class Rule {

    @Getter
    @EqualsAndHashCode
    @AllArgsConstructor
    @ToString
    public static class Test {
        private final String id;
        private final String input;
        private final String normalized;
        private final String tokenized;
        private final String output;
        private final String timespan;
    }

    private final String id;
    private final String inputMask;
    private final String inputPattern;
    private final String outputMask;
    private final String outputPattern;
    private final Set<Test> tests;

}
