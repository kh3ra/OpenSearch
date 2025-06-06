/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.tasks;

import org.opensearch.core.common.io.stream.StreamInput;

import java.io.IOException;

/**
 * Holds monitoring service stats specific to search task.
 */
public class SearchTaskCancellationStats extends BaseSearchTaskCancellationStats {

    public SearchTaskCancellationStats(long currentTaskCount, long totalTaskCount) {
        super(currentTaskCount, totalTaskCount);
    }

    public SearchTaskCancellationStats(StreamInput in) throws IOException {
        super(in);
    }
}
