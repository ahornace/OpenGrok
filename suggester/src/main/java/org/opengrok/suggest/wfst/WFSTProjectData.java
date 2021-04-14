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

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.spell.LuceneDictionary;
import org.apache.lucene.search.suggest.Lookup;
import org.apache.lucene.search.suggest.fst.WFSTCompletionLookup;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.opengrok.suggest.popular.PopularityCounter;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

public class WFSTProjectData implements Closeable {

    private static final String TMP_DIR_PROPERTY = "java.io.tmpdir";

    private static final String WFST_TEMP_FILE_PREFIX = "opengrok_suggester_wfst";

    private static final String WFST_FILE_SUFFIX = ".wfst";

    private static final Logger logger = Logger.getLogger(WFSTProjectData.class.getName());

    private final Map<String, WFSTCompletionLookup> lookups = new HashMap<>();

    private final Map<String, Double> averageLengths = new HashMap<>();

    private final Directory tempDir;

    private final Directory indexDir;

    private final Path suggesterDir;

    private final Function<String, PopularityCounter> popularityCounterProvider;

    private Set<String> fields;

    public WFSTProjectData(
            final Path suggesterDir,
            final Directory indexDir,
            final Function<String, PopularityCounter> popularityCounterProvider
    ) throws IOException {
        this.indexDir = indexDir;
        this.suggesterDir = suggesterDir;
        this.popularityCounterProvider = popularityCounterProvider;
        tempDir = FSDirectory.open(Paths.get(System.getProperty(TMP_DIR_PROPERTY)));
    }

    public void setFields(final Set<String> fields) {
        this.fields = Set.copyOf(fields);
    }

    public void init(final boolean areStoredDataUpToDate) throws IOException {
        if (hasStoredData() && areStoredDataUpToDate) {
            loadStoredWFSTs();
        } else {
            build();
        }
    }

    private boolean hasStoredData() {
        if (!suggesterDir.toFile().exists()) {
            return false;
        }

        File[] children = suggesterDir.toFile().listFiles();
        return children != null && children.length > 0;
    }

    private void loadStoredWFSTs() throws IOException {
        try (IndexReader indexReader = DirectoryReader.open(indexDir)) {
            for (String field : fields) {

                File wfstFile = getWFSTFile(field);
                if (wfstFile.exists()) {
                    WFSTCompletionLookup wfst = loadStoredWFST(wfstFile);
                    lookups.put(field, wfst);
                } else {
                    logger.log(Level.INFO, "Missing WFST file for {0} field in {1}, creating a new one",
                            new Object[] {field, suggesterDir});

                    WFSTCompletionLookup lookup = build(indexReader, field);
                    store(lookup, field);

                    lookups.put(field, lookup);
                }
            }
        }
    }

    private WFSTCompletionLookup loadStoredWFST(final File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            WFSTCompletionLookup lookup = createWFST();
            lookup.load(fis);
            return lookup;
        }
    }

    private WFSTCompletionLookup createWFST() {
        return new WFSTCompletionLookup(tempDir, WFST_TEMP_FILE_PREFIX);
    }

    private File getWFSTFile(final String field) {
        return getFile(field + WFST_FILE_SUFFIX);
    }

    private File getFile(final String fileName) {
        return suggesterDir.resolve(fileName).toFile();
    }

    private WFSTCompletionLookup build(final IndexReader indexReader, final String field) throws IOException {
        WFSTInputIterator iterator = new WFSTInputIterator(
                new LuceneDictionary(indexReader, field).getEntryIterator(), indexReader, field,
                popularityCounterProvider.apply(field));

        WFSTCompletionLookup lookup = createWFST();
        lookup.build(iterator);

        if (lookup.getCount() > 0) {
            double averageLength = (double) iterator.getTermLengthAccumulator() / lookup.getCount();
            averageLengths.put(field, averageLength);
        }

        return lookup;
    }

    public void build() throws IOException {
        try (IndexReader indexReader = DirectoryReader.open(indexDir)) {
            for (String field : fields) {
                WFSTCompletionLookup lookup = build(indexReader, field);
                store(lookup, field);

                lookups.put(field, lookup);
            }
        }
    }

    private void store(final WFSTCompletionLookup wfst, final String field) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(getWFSTFile(field))) {
            wfst.store(fos);
        }
    }

    /**
     * Looks up the terms in the WFST data structure.
     * @param field term field
     * @param prefix prefix the returned terms must contain
     * @param resultSize number of terms to return
     * @return terms with highest score
     */
    public List<Lookup.LookupResult> lookup(final String field, final String prefix, final int resultSize) {
        try {
            WFSTCompletionLookup lookup = lookups.get(field);
            if (lookup == null) {
                logger.log(Level.WARNING, "No WFST for field {0} in {1}", new Object[] {field, suggesterDir});
                return Collections.emptyList();
            }
            return lookup.lookup(prefix, false, resultSize);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not perform lookup in {0} for {1}:{2}",
                    new Object[] {suggesterDir, field, prefix});
        }
        return Collections.emptyList();
    }

    public Optional<Double> getAverageLength(final String field) {
        if (averageLengths.containsKey(field)) {
            return Optional.of(averageLengths.get(field));
        }
        return Optional.empty();
    }

    public long getTermCount(final String field) {
        return lookups.get(field).getCount();
    }

    public boolean hasTerm(final Term term) {
        return lookups.get(term.field()).get(term.text()) != null;
    }

    public WFSTCompletionLookup getWfstLookup(final String field) {
        return lookups.get(field);
    }

    @Override
    public void close() throws IOException {
        tempDir.close();
    }
}
