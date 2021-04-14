/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License (the "License").
 * You may not use this file except in compliance with the License.
 *
 * See LICENSE.txt included in this distribution for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at LICENSE.txt.
 * If applicable, add the following below this CDDL HEADER, with the
 * fields enclosed by brackets "[]" replaced with your own identifying
 * information: Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 */

/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
 */
package org.opengrok.suggest.wfst;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.suggest.InputIterator;
import org.apache.lucene.util.BytesRef;
import org.opengrok.suggest.SuggesterSearcher;
import org.opengrok.suggest.SuggesterUtils;
import org.opengrok.suggest.popular.PopularityCounter;

import java.io.IOException;
import java.util.Set;

/**
 * An {@link InputIterator} for WFST data structure with most popular completion support.
 */
public class WFSTInputIterator implements InputIterator {

    private static final int DEFAULT_WEIGHT = 0;

    private static final int MAX_TERM_SIZE = Short.MAX_VALUE - 3;

    private final InputIterator wrapped;

    private final IndexReader indexReader;

    private final String field;

    private long termLengthAccumulator = 0;

    private final PopularityCounter searchCounts;

    private BytesRef last;

    public WFSTInputIterator(
            final InputIterator wrapped,
            final IndexReader indexReader,
            final String field,
            final PopularityCounter searchCounts
    ) {
        this.wrapped = wrapped;
        this.indexReader = indexReader;
        this.field = field;
        this.searchCounts = searchCounts;
    }

    public long getTermLengthAccumulator() {
        return termLengthAccumulator;
    }

    @Override
    public long weight() {
        if (last != null) {
            int add = searchCounts.get(last);

            return SuggesterUtils.computeScore(indexReader, field, last)
                    + add * SuggesterSearcher.TERM_ALREADY_SEARCHED_MULTIPLIER;
        }

        return DEFAULT_WEIGHT;
    }

    @Override
    public BytesRef payload() {
        return wrapped.payload();
    }

    @Override
    public boolean hasPayloads() {
        return wrapped.hasPayloads();
    }

    @Override
    public Set<BytesRef> contexts() {
        return wrapped.contexts();
    }

    @Override
    public boolean hasContexts() {
        return wrapped.hasContexts();
    }

    @Override
    public BytesRef next() throws IOException {
        last = wrapped.next();

        // skip very large terms because of the buffer exception
        while (last != null && last.length > MAX_TERM_SIZE) {
            last = wrapped.next();
        }

        if (last != null) {
            termLengthAccumulator += last.length;
        }

        return last;
    }
}
