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
package de.ddb.labs.timeparser.replacement;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/**
 * Contains a source expression and a target string for normalization or literal
 * replacement. Can be used for both simple string replacements and regex-driven
 * normalizations.
 */
@Getter
@EqualsAndHashCode
@ToString
public class Replacement {

    private final String from;
    private final String to;
    private final boolean regex;
    private final Pattern pattern;

    public Replacement(final String from, final String to) {
        this(from, to, false);
    }

    public Replacement(final String from, final String to, final boolean regex) {
        this.from = from;
        this.to = to;
        this.regex = regex;
        this.pattern = regex ? Pattern.compile(from) : null;
    }

    public String applyTo(final String input) {
        return replaceWith(input, to);
    }

    public String replaceWith(final String input, final String replacementValue) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        if (regex) {
            return pattern.matcher(input).replaceAll(Matcher.quoteReplacement(replacementValue));
        }
        return input.contains(from) ? input.replace(from, replacementValue) : input;
    }

    public static String applyAll(final String input, final List<Replacement> replacements) {
        String result = input;
        for (final Replacement replacement : replacements) {
            result = replacement.applyTo(result);
        }
        return result;
    }

    public static String replaceAllWith(final String input, final List<Replacement> replacements,
            final String replacementValue) {
        String result = input;
        for (final Replacement replacement : replacements) {
            result = replacement.replaceWith(result, replacementValue);
        }
        return result;
    }
}
