/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.aggregation;

// begin generated imports
import org.apache.lucene.util.Accountable;
import org.apache.lucene.util.RamUsageEstimator;
import org.elasticsearch.common.breaker.CircuitBreaker;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.util.ObjectArray;
import org.elasticsearch.compute.ann.GroupingAggregator;
import org.elasticsearch.compute.ann.IntermediateState;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BlockFactory;
import org.elasticsearch.compute.data.DoubleBlock;
import org.elasticsearch.compute.data.DoubleVector;
import org.elasticsearch.compute.data.FloatBlock;
import org.elasticsearch.compute.data.IntBlock;
import org.elasticsearch.compute.data.IntVector;
import org.elasticsearch.compute.data.LongBlock;
import org.elasticsearch.compute.operator.DriverContext;
import org.elasticsearch.core.Releasable;
import org.elasticsearch.core.Releasables;
// end generated imports

/**
 * A rate grouping aggregation definition for long.
 * This class is generated. Edit `X-RateAggregator.java.st` instead.
 */
@GroupingAggregator(
    value = {
        @IntermediateState(name = "timestamps", type = "LONG_BLOCK"),
        @IntermediateState(name = "values", type = "LONG_BLOCK"),
        @IntermediateState(name = "sampleCounts", type = "INT"),
        @IntermediateState(name = "resets", type = "DOUBLE") }
)
public class RateLongAggregator {

    public static LongRateGroupingState initGrouping(DriverContext driverContext) {
        return new LongRateGroupingState(driverContext.bigArrays(), driverContext.breaker());
    }

    public static void combine(LongRateGroupingState current, int groupId, long timestamp, long value) {
        current.append(groupId, timestamp, value);
    }

    public static void combineIntermediate(
        LongRateGroupingState current,
        int groupId,
        LongBlock timestamps,
        LongBlock values,
        int sampleCount,
        double reset,
        int otherPosition
    ) {
        current.combine(groupId, timestamps, values, sampleCount, reset, otherPosition);
    }

    public static Block evaluateFinal(LongRateGroupingState state, IntVector selected, GroupingAggregatorEvaluationContext evalContext) {
        return state.evaluateFinal(selected, evalContext);
    }

    private static class LongRateState {
        static final long BASE_RAM_USAGE = RamUsageEstimator.sizeOfObject(LongRateState.class);
        final long[] timestamps; // descending order
        final long[] values;
        // the timestamps and values arrays might have collapsed to fewer values than the actual sample count
        int sampleCount = 0;
        double reset = 0;

        LongRateState(int initialSize) {
            this.timestamps = new long[initialSize];
            this.values = new long[initialSize];
        }

        LongRateState(long[] ts, long[] vs) {
            this.timestamps = ts;
            this.values = vs;
            this.sampleCount = values.length;
        }

        private long dv(long v0, long v1) {
            // counter reset detection
            return v0 > v1 ? v1 : v1 - v0;
        }

        void append(long t, long v) {
            assert timestamps.length == 2 : "expected two timestamps; got " + timestamps.length;
            assert t < timestamps[1] : "@timestamp goes backward: " + t + " >= " + timestamps[1];
            reset += dv(v, values[1]) + dv(values[1], values[0]) - dv(v, values[0]);
            timestamps[1] = t;
            values[1] = v;
            sampleCount++;
        }

        int entries() {
            return timestamps.length;
        }

        static long bytesUsed(int entries) {
            var ts = RamUsageEstimator.alignObjectSize(RamUsageEstimator.NUM_BYTES_ARRAY_HEADER + (long) Long.BYTES * entries);
            var vs = RamUsageEstimator.alignObjectSize(RamUsageEstimator.NUM_BYTES_ARRAY_HEADER + (long) Long.BYTES * entries);
            return BASE_RAM_USAGE + ts + vs;
        }
    }

    public static final class LongRateGroupingState implements Releasable, Accountable, GroupingAggregatorState {
        private ObjectArray<LongRateState> states;
        private final BigArrays bigArrays;
        private final CircuitBreaker breaker;
        private long stateBytes; // for individual states

        LongRateGroupingState(BigArrays bigArrays, CircuitBreaker breaker) {
            this.bigArrays = bigArrays;
            this.breaker = breaker;
            this.states = bigArrays.newObjectArray(1);
        }

        void ensureCapacity(int groupId) {
            states = bigArrays.grow(states, groupId + 1);
        }

        void adjustBreaker(long bytes) {
            breaker.addEstimateBytesAndMaybeBreak(bytes, "<<rate aggregation>>");
            stateBytes += bytes;
            assert stateBytes >= 0 : stateBytes;
        }

        void append(int groupId, long timestamp, long value) {
            ensureCapacity(groupId);
            var state = states.get(groupId);
            if (state == null) {
                adjustBreaker(LongRateState.bytesUsed(1));
                state = new LongRateState(new long[] { timestamp }, new long[] { value });
                states.set(groupId, state);
            } else {
                if (state.entries() == 1) {
                    adjustBreaker(LongRateState.bytesUsed(2));
                    state = new LongRateState(new long[] { state.timestamps[0], timestamp }, new long[] { state.values[0], value });
                    states.set(groupId, state);
                    adjustBreaker(-LongRateState.bytesUsed(1)); // old state
                } else {
                    state.append(timestamp, value);
                }
            }
        }

        void combine(int groupId, LongBlock timestamps, LongBlock values, int sampleCount, double reset, int otherPosition) {
            final int valueCount = timestamps.getValueCount(otherPosition);
            if (valueCount == 0) {
                return;
            }
            final int firstIndex = timestamps.getFirstValueIndex(otherPosition);
            ensureCapacity(groupId);
            var state = states.get(groupId);
            if (state == null) {
                adjustBreaker(LongRateState.bytesUsed(valueCount));
                state = new LongRateState(valueCount);
                state.reset = reset;
                state.sampleCount = sampleCount;
                states.set(groupId, state);
                // TODO: add bulk_copy to Block
                for (int i = 0; i < valueCount; i++) {
                    state.timestamps[i] = timestamps.getLong(firstIndex + i);
                    state.values[i] = values.getLong(firstIndex + i);
                }
            } else {
                adjustBreaker(LongRateState.bytesUsed(state.entries() + valueCount));
                var newState = new LongRateState(state.entries() + valueCount);
                newState.reset = state.reset + reset;
                newState.sampleCount = state.sampleCount + sampleCount;
                states.set(groupId, newState);
                merge(state, newState, firstIndex, valueCount, timestamps, values);
                adjustBreaker(-LongRateState.bytesUsed(state.entries())); // old state
            }
        }

        void merge(LongRateState curr, LongRateState dst, int firstIndex, int rightCount, LongBlock timestamps, LongBlock values) {
            int i = 0, j = 0, k = 0;
            final int leftCount = curr.entries();
            while (i < leftCount && j < rightCount) {
                final var t1 = curr.timestamps[i];
                final var t2 = timestamps.getLong(firstIndex + j);
                if (t1 > t2) {
                    dst.timestamps[k] = t1;
                    dst.values[k] = curr.values[i];
                    ++i;
                } else {
                    dst.timestamps[k] = t2;
                    dst.values[k] = values.getLong(firstIndex + j);
                    ++j;
                }
                ++k;
            }
            if (i < leftCount) {
                System.arraycopy(curr.timestamps, i, dst.timestamps, k, leftCount - i);
                System.arraycopy(curr.values, i, dst.values, k, leftCount - i);
            }
            while (j < rightCount) {
                dst.timestamps[k] = timestamps.getLong(firstIndex + j);
                dst.values[k] = values.getLong(firstIndex + j);
                ++k;
                ++j;
            }
        }

        LongRateState mergeState(LongRateState s1, LongRateState s2) {
            var newLen = s1.entries() + s2.entries();
            adjustBreaker(LongRateState.bytesUsed(newLen));
            var dst = new LongRateState(newLen);
            dst.reset = s1.reset + s2.reset;
            dst.sampleCount = s1.sampleCount + s2.sampleCount;
            int i = 0, j = 0, k = 0;
            while (i < s1.entries() && j < s2.entries()) {
                if (s1.timestamps[i] > s2.timestamps[j]) {
                    dst.timestamps[k] = s1.timestamps[i];
                    dst.values[k] = s1.values[i];
                    ++i;
                } else {
                    dst.timestamps[k] = s2.timestamps[j];
                    dst.values[k] = s2.values[j];
                    ++j;
                }
                ++k;
            }
            System.arraycopy(s1.timestamps, i, dst.timestamps, k, s1.entries() - i);
            System.arraycopy(s1.values, i, dst.values, k, s1.entries() - i);
            System.arraycopy(s2.timestamps, j, dst.timestamps, k, s2.entries() - j);
            System.arraycopy(s2.values, j, dst.values, k, s2.entries() - j);
            return dst;
        }

        @Override
        public long ramBytesUsed() {
            return states.ramBytesUsed() + stateBytes;
        }

        @Override
        public void close() {
            Releasables.close(states, () -> adjustBreaker(-stateBytes));
        }

        @Override
        public void toIntermediate(Block[] blocks, int offset, IntVector selected, DriverContext driverContext) {
            assert blocks.length >= offset + 3 : "blocks=" + blocks.length + ",offset=" + offset;
            final BlockFactory blockFactory = driverContext.blockFactory();
            final int positionCount = selected.getPositionCount();
            try (
                LongBlock.Builder timestamps = blockFactory.newLongBlockBuilder(positionCount * 2);
                LongBlock.Builder values = blockFactory.newLongBlockBuilder(positionCount * 2);
                IntVector.FixedBuilder sampleCounts = blockFactory.newIntVectorFixedBuilder(positionCount);
                DoubleVector.FixedBuilder resets = blockFactory.newDoubleVectorFixedBuilder(positionCount)
            ) {
                for (int i = 0; i < positionCount; i++) {
                    final var groupId = selected.getInt(i);
                    final var state = groupId < states.size() ? states.get(groupId) : null;
                    if (state != null) {
                        timestamps.beginPositionEntry();
                        for (long t : state.timestamps) {
                            timestamps.appendLong(t);
                        }
                        timestamps.endPositionEntry();

                        values.beginPositionEntry();
                        for (long v : state.values) {
                            values.appendLong(v);
                        }
                        values.endPositionEntry();
                        sampleCounts.appendInt(i, state.sampleCount);
                        resets.appendDouble(i, state.reset);
                    } else {
                        timestamps.appendNull();
                        values.appendNull();
                        sampleCounts.appendInt(i, 0);
                        resets.appendDouble(i, 0);
                    }
                }
                blocks[offset] = timestamps.build();
                blocks[offset + 1] = values.build();
                blocks[offset + 2] = sampleCounts.build().asBlock();
                blocks[offset + 3] = resets.build().asBlock();
            }
        }

        private static double computeRateWithoutExtrapolate(LongRateState state) {
            final int len = state.entries();
            assert len >= 2 : "rate requires at least two samples; got " + len;
            final long firstTS = state.timestamps[state.timestamps.length - 1];
            final long lastTS = state.timestamps[0];
            double reset = state.reset;
            for (int i = 1; i < len; i++) {
                if (state.values[i - 1] < state.values[i]) {
                    reset += state.values[i];
                }
            }
            final double firstValue = state.values[len - 1];
            final double lastValue = state.values[0] + reset;
            return (lastValue - firstValue) * 1000.0 / (lastTS - firstTS);
        }

        /**
         * Credit to PromQL for this extrapolation algorithm:
         * If samples are close enough to the rangeStart and rangeEnd, we extrapolate the rate all the way to the boundary in question.
         * "Close enough" is defined as "up to 10% more than the average duration between samples within the range".
         * Essentially, we assume a more or less regular spacing between samples. If we don't see a sample where we would expect one,
         * we assume the series does not cover the whole range but starts and/or ends within the range.
         * We still extrapolate the rate in this case, but not all the way to the boundary, only by half of the average duration between
         * samples (which is our guess for where the series actually starts or ends).
         */
        private static double extrapolateRate(LongRateState state, long rangeStart, long rangeEnd) {
            final int len = state.entries();
            assert len >= 2 : "rate requires at least two samples; got " + len;
            final long firstTS = state.timestamps[state.timestamps.length - 1];
            final long lastTS = state.timestamps[0];
            double reset = state.reset;
            for (int i = 1; i < len; i++) {
                if (state.values[i - 1] < state.values[i]) {
                    reset += state.values[i];
                }
            }
            double firstValue = state.values[len - 1];
            double lastValue = state.values[0] + reset;
            final double sampleTS = lastTS - firstTS;
            final double averageSampleInterval = sampleTS / state.sampleCount;
            final double slope = (lastValue - firstValue) / sampleTS;
            double startGap = firstTS - rangeStart;
            if (startGap > 0) {
                if (startGap > averageSampleInterval * 1.1) {
                    startGap = averageSampleInterval / 2.0;
                }
                firstValue = Math.max(0.0, firstValue - startGap * slope);
            }
            double endGap = rangeEnd - lastTS;
            if (endGap > 0) {
                if (endGap > averageSampleInterval * 1.1) {
                    endGap = averageSampleInterval / 2.0;
                }
                lastValue = lastValue + endGap * slope;
            }
            return (lastValue - firstValue) * 1000.0 / (rangeEnd - rangeStart);
        }

        Block evaluateFinal(IntVector selected, GroupingAggregatorEvaluationContext evalContext) {
            int positionCount = selected.getPositionCount();
            try (DoubleBlock.Builder rates = evalContext.blockFactory().newDoubleBlockBuilder(positionCount)) {
                for (int p = 0; p < positionCount; p++) {
                    final var groupId = selected.getInt(p);
                    final var state = groupId < states.size() ? states.get(groupId) : null;
                    if (state == null || state.sampleCount < 2) {
                        rates.appendNull();
                        continue;
                    }
                    int len = state.entries();
                    final double rate;
                    if (evalContext instanceof TimeSeriesGroupingAggregatorEvaluationContext tsContext) {
                        rate = extrapolateRate(state, tsContext.rangeStartInMillis(groupId), tsContext.rangeEndInMillis(groupId));
                    } else {
                        rate = computeRateWithoutExtrapolate(state);
                    }
                    rates.appendDouble(rate);
                }
                return rates.build();
            }
        }

        @Override
        public void enableGroupIdTracking(SeenGroupIds seenGroupIds) {
            // noop - we handle the null states inside `toIntermediate` and `evaluateFinal`
        }
    }
}
