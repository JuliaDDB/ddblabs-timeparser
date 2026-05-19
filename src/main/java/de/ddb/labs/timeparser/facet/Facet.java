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

import lombok.EqualsAndHashCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

/**
 * Facet metadata used to map parsed year ranges to index facets.
 */
@Getter
@EqualsAndHashCode
@AllArgsConstructor
@ToString
public class Facet {

    private final String id;
    private final String notation;
    private final Long earliestDate;
    private final Long latestDate;
    private final String prefLabelDe;
    private final String prefLabelEn;
    private final int sortOrder;

}
