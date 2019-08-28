package org.opengrok.indexer;

import com.codahale.metrics.MetricRegistry;

public class Metrics {

    public static final String REQUESTS = "requests";

    private static final MetricRegistry instance = new MetricRegistry();

    public static MetricRegistry getInstance() {
        return instance;
    }

}
