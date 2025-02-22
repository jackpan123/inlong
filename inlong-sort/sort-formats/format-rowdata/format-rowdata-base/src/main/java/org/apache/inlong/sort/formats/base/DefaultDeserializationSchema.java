/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.inlong.sort.formats.base;

import org.apache.inlong.sort.formats.metrics.FormatMetricGroup;

import org.apache.flink.api.common.serialization.DeserializationSchema;
import org.apache.flink.metrics.MetricGroup;
import org.apache.flink.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Objects;

import static org.apache.flink.util.Preconditions.checkArgument;

/**
 * The base implementation of {@link DeserializationSchema}.
 */
public abstract class DefaultDeserializationSchema<T> implements DeserializationSchema<T> {

    private static final long serialVersionUID = 1L;

    private static final Logger LOG = LoggerFactory.getLogger(DefaultDeserializationSchema.class);

    /**
     * If true, the deserialization error will be ignored.
     */
    private final boolean ignoreErrors;

    /**
     * If true, a parsing error is occurred.
     */
    private boolean errorOccurred = false;

    /**
     * The format metric group.
     */
    protected transient FormatMetricGroup formatMetricGroup;

    public DefaultDeserializationSchema(boolean ignoreErrors) {
        this.ignoreErrors = ignoreErrors;
    }

    @Override
    public void open(DeserializationSchema.InitializationContext context) {
        try {
            MetricGroup metricGroup = context.getMetricGroup();

            checkArgument(metricGroup instanceof FormatMetricGroup,
                    "Expecting FormatMetricGroup, but got " + metricGroup.getClass().getName());

            this.formatMetricGroup = (FormatMetricGroup) metricGroup;
        } catch (Exception ignore) {
            LOG.warn("FormatGroup initialization error, no format metric will be accumulated", ignore);
        }
    }

    /**
     * Deserialize the data and handle the failure.
     *
     * <p>Note: Returns null if the message could not be properly deserialized.
     */
    @Override
    public T deserialize(byte[] bytes) throws IOException {
        try {
            T result = deserializeInternal(bytes);
            // reset error state after deserialize success
            errorOccurred = false;
            return result;
        } catch (Exception e) {
            errorOccurred = true;
            if (formatMetricGroup != null) {
                formatMetricGroup.getNumRecordsDeserializeError().inc(1L);
            }
            if (ignoreErrors) {
                if (formatMetricGroup != null) {
                    formatMetricGroup.getNumRecordsDeserializeErrorIgnored().inc(1L);
                }
                if (bytes == null) {
                    LOG.warn("Could not properly deserialize the data null.");
                } else {
                    LOG.warn("Could not properly deserialize the data {}.",
                            StringUtils.byteToHexString(bytes), e);
                }
                return null;
            }
            throw new IOException("Failed to deserialize data " +
                    StringUtils.byteToHexString(bytes), e);
        }
    }

    public boolean skipCurrentRecord(T element) {
        return ignoreErrors && errorOccurred;
    }

    protected abstract T deserializeInternal(byte[] bytes) throws IOException;

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (object == null || getClass() != object.getClass()) {
            return false;
        }
        DefaultDeserializationSchema<?> that = (DefaultDeserializationSchema<?>) object;
        return Objects.equals(ignoreErrors, that.ignoreErrors);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ignoreErrors);
    }
}
