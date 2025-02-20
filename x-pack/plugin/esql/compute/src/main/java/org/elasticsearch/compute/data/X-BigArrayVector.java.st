/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.data;

import org.apache.lucene.util.RamUsageEstimator;
import org.elasticsearch.common.util.$if(boolean)$Bit$else$$Type$$endif$Array;
import org.elasticsearch.core.Releasable;

/**
 * Vector implementation that defers to an enclosed {@link $if(boolean)$Bit$else$$Type$$endif$Array}.
 * Does not take ownership of the array and does not adjust circuit breakers to account for it.
 * This class is generated. Do not edit it.
 */
public final class $Type$BigArrayVector extends AbstractVector implements $Type$Vector, Releasable {

    private static final long BASE_RAM_BYTES_USED = 0; // FIXME

    private final $if(boolean)$Bit$else$$Type$$endif$Array values;

    public $Type$BigArrayVector($if(boolean)$Bit$else$$Type$$endif$Array values, int positionCount, BlockFactory blockFactory) {
        super(positionCount, blockFactory);
        this.values = values;
    }

    @Override
    public $Type$Block asBlock() {
        return new $Type$VectorBlock(this);
    }

    @Override
    public $type$ get$Type$(int position) {
        return values.get(position);
    }

    @Override
    public ElementType elementType() {
        return ElementType.$TYPE$;
    }

    @Override
    public boolean isConstant() {
        return false;
    }

    @Override
    public long ramBytesUsed() {
        return BASE_RAM_BYTES_USED + RamUsageEstimator.sizeOf(values);
    }

    @Override
    public $Type$Vector filter(int... positions) {
        var blockFactory = blockFactory();
   $if(boolean)$
        final BitArray filtered = new BitArray(positions.length, blockFactory.bigArrays());
        for (int i = 0; i < positions.length; i++) {
            if (values.get(positions[i])) {
                filtered.set(i);
            }
        }
   $else$
        final $Type$Array filtered = blockFactory.bigArrays().new$Type$Array(positions.length, true);
        for (int i = 0; i < positions.length; i++) {
            filtered.set(i, values.get(positions[i]));
        }
   $endif$
        return new $Type$BigArrayVector(filtered, positions.length, blockFactory);
    }

    @Override
    public void closeInternal() {
        // The circuit breaker that tracks the values {@link $if(boolean)$Bit$else$$Type$$endif$Array} is adjusted outside
        // of this class.
        values.close();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof $Type$Vector that) {
            return $Type$Vector.equals(this, that);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return $Type$Vector.hash(this);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[positions=" + getPositionCount() + ", values=" + values + ']';
    }
}
