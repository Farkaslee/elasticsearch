/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.search.aggregations.bucket.terms;

import org.apache.lucene.index.AtomicReaderContext;
import org.elasticsearch.ElasticsearchIllegalArgumentException;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.search.aggregations.*;
import org.elasticsearch.search.aggregations.Aggregator.BucketAggregationMode;
import org.elasticsearch.search.aggregations.AggregatorFactories;
import org.elasticsearch.search.aggregations.bucket.terms.support.IncludeExclude;
import org.elasticsearch.search.aggregations.support.AggregationContext;
import org.elasticsearch.search.aggregations.support.ValuesSource;
import org.elasticsearch.search.aggregations.support.ValuesSourceAggregatorFactory;
import org.elasticsearch.search.aggregations.support.ValuesSourceConfig;
import org.elasticsearch.search.aggregations.support.format.ValueFormatter;
import org.elasticsearch.search.aggregations.support.format.ValueParser;

/**
 *
 */
public class TermsAggregatorFactory extends ValuesSourceAggregatorFactory {

    public enum ExecutionMode {
        MAP(new ParseField("map")) {

            @Override
            Aggregator create(String name, AggregatorFactories factories, ValuesSource valuesSource, long estimatedBucketCount,
                    InternalOrder order, int requiredSize, int shardSize, long minDocCount, IncludeExclude includeExclude,
                    AggregationContext aggregationContext, Aggregator parent) {
                return new StringTermsAggregator(name, factories, valuesSource, estimatedBucketCount, order, requiredSize, shardSize, minDocCount, includeExclude, aggregationContext, parent);
            }

        },
        ORDINALS(new ParseField("ordinals")) {

            @Override
            Aggregator create(String name, AggregatorFactories factories, ValuesSource valuesSource, long estimatedBucketCount,
                    InternalOrder order, int requiredSize, int shardSize, long minDocCount, IncludeExclude includeExclude,
                    AggregationContext aggregationContext, Aggregator parent) {
                if (includeExclude != null) {
                    throw new ElasticsearchIllegalArgumentException("The `" + this + "` execution mode cannot filter terms.");
                }
                return new StringTermsAggregator.WithOrdinals(name, factories, (ValuesSource.Bytes.WithOrdinals) valuesSource, estimatedBucketCount, order, requiredSize, shardSize, minDocCount, aggregationContext, parent);
            }

        };

        public static ExecutionMode fromString(String value) {
            for (ExecutionMode mode : values()) {
                if (mode.parseField.match(value)) {
                    return mode;
                }
            }
            throw new ElasticsearchIllegalArgumentException("Unknown `execution_hint`: [" + value + "], expected any of " + values());
        }

        private final ParseField parseField;

        ExecutionMode(ParseField parseField) {
            this.parseField = parseField;
        }

        abstract Aggregator create(String name, AggregatorFactories factories, ValuesSource valuesSource, long estimatedBucketCount,
                InternalOrder order, int requiredSize, int shardSize, long minDocCount,
                IncludeExclude includeExclude, AggregationContext aggregationContext, Aggregator parent);

        @Override
        public String toString() {
            return parseField.getPreferredName();
        }
    }

    private final InternalOrder order;
    private final int requiredSize;
    private final int shardSize;
    private final long minDocCount;
    private final IncludeExclude includeExclude;
    private final String executionHint;

    public TermsAggregatorFactory(String name, ValuesSourceConfig config, ValueFormatter formatter, ValueParser parser,
            InternalOrder order, int requiredSize, int shardSize, long minDocCount, IncludeExclude includeExclude, String executionHint) {

        super(name, StringTerms.TYPE.name(), config, formatter, parser);
        this.order = order;
        this.requiredSize = requiredSize;
        this.shardSize = shardSize;
        this.minDocCount = minDocCount;
        this.includeExclude = includeExclude;
        this.executionHint = executionHint;
    }

    @Override
    protected Aggregator createUnmapped(AggregationContext aggregationContext, Aggregator parent) {
        final InternalAggregation aggregation = new UnmappedTerms(name, order, requiredSize, minDocCount);
        return new NonCollectingAggregator(name, aggregationContext, parent) {
            @Override
            public InternalAggregation buildEmptyAggregation() {
                return aggregation;
            }
        };
    }

    private static boolean hasParentBucketAggregator(Aggregator parent) {
        if (parent == null) {
            return false;
        } else if (parent.bucketAggregationMode() == BucketAggregationMode.PER_BUCKET) {
            return true;
        } else {
            return hasParentBucketAggregator(parent.parent());
        }
    }

    private boolean shouldUseOrdinals(Aggregator parent, ValuesSource valuesSource, AggregationContext context) {
        // if there is a parent bucket aggregator the number of instances of this aggregator is going to be unbounded and most instances
        // may only aggregate few documents, so don't use ordinals
        if (hasParentBucketAggregator(parent)) {
            return false;
        }

        // be defensive: if the number of unique values is unknown, don't use ordinals
        final long maxNumUniqueValues = valuesSource.metaData().maxAtomicUniqueValuesCount();
        if (maxNumUniqueValues == -1) {
            return false;
        }

        // if the number of unique values is high compared to the document count, then ordinals are only going to make things slower
        int maxDoc = 0;
        for (AtomicReaderContext ctx : context.searchContext().searcher().getTopReaderContext().reader().leaves()) {
            maxDoc = Math.max(maxDoc, ctx.reader().maxDoc());
        }
        if (maxNumUniqueValues > (maxDoc >>> 4)) {
            return false;
        }

        return true;
    }

    @Override
    protected Aggregator create(ValuesSource valuesSource, long expectedBucketsCount, AggregationContext aggregationContext, Aggregator parent) {
        long estimatedBucketCount = valuesSource.metaData().maxAtomicUniqueValuesCount();
        if (estimatedBucketCount < 0) {
            // there isn't an estimation available.. 50 should be a good start
            estimatedBucketCount = 50;
        }

        // adding an upper bound on the estimation as some atomic field data in the future (binary doc values) and not
        // going to know their exact cardinality and will return upper bounds in AtomicFieldData.getNumberUniqueValues()
        // that may be largely over-estimated.. the value chosen here is arbitrary just to play nice with typical CPU cache
        //
        // Another reason is that it may be faster to resize upon growth than to start directly with the appropriate size.
        // And that all values are not necessarily visited by the matches.
        estimatedBucketCount = Math.min(estimatedBucketCount, 512);

        if (valuesSource instanceof ValuesSource.Bytes) {
            ExecutionMode execution = null;
            if (executionHint != null) {
                execution = ExecutionMode.fromString(executionHint);
            }

            // In some cases, using ordinals is just not supported: override it
            if (!(valuesSource instanceof ValuesSource.Bytes.WithOrdinals)) {
                execution = ExecutionMode.MAP;
            } else if (includeExclude != null) {
                execution = ExecutionMode.MAP;
            }

            if (execution == null) {
                // Let's try to use a good default
                if ((valuesSource instanceof ValuesSource.Bytes.WithOrdinals)
                        && shouldUseOrdinals(parent, valuesSource, aggregationContext)) {
                    execution = ExecutionMode.ORDINALS;
                } else {
                    execution = ExecutionMode.MAP;
                }
            }
            assert execution != null;

            return execution.create(name, factories, valuesSource, estimatedBucketCount, order, requiredSize, shardSize, minDocCount, includeExclude, aggregationContext, parent);
        }

        if (includeExclude != null) {
            throw new AggregationExecutionException("Aggregation [" + name + "] cannot support the include/exclude " +
                    "settings as it can only be applied to string values");
        }

        if (valuesSource instanceof ValuesSource.Numeric) {
            if (((ValuesSource.Numeric) valuesSource).isFloatingPoint()) {
                return new DoubleTermsAggregator(name, factories, (ValuesSource.Numeric) valuesSource, formatter, estimatedBucketCount, order, requiredSize, shardSize, minDocCount, aggregationContext, parent);
            }
            return new LongTermsAggregator(name, factories, (ValuesSource.Numeric) valuesSource, formatter, estimatedBucketCount, order, requiredSize, shardSize, minDocCount, aggregationContext, parent);
        }

        throw new AggregationExecutionException("terms aggregation cannot be applied to field [" + config.fieldContext().field() +
                "]. It can only be applied to numeric or string fields.");
    }

}
