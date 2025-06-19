/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.index.store.RemoteSegmentStoreDirectory;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry for tracking merged segments during replication.
 * Uses static holder pattern for lazy initialization.
 */
public final class MergedSegmentRegistry {
    private static final Logger logger = LogManager.getLogger(MergedSegmentRegistry.class);

    /**
     * Static holder class for lazy initialization
     */
    private static class Holder {
        private static final MergedSegmentRegistry INSTANCE = new MergedSegmentRegistry();
    }

    // Core data structure using concurrent set for thread safety
    private final Set<String> mergedSegments;

    /**
     * Private constructor to enforce singleton pattern
     */
    private MergedSegmentRegistry() {
        this.mergedSegments = ConcurrentHashMap.newKeySet();
    }

    /**
     * Get the singleton instance.
     * Instance is created only when this method is first called.
     */
    public static MergedSegmentRegistry getInstance() {
        return Holder.INSTANCE;
    }

    /**
     * Register a merged segment file
     * @param fileName Name of the merged segment file
     */
    public void registerMergedSegment(String fileName) {
        mergedSegments.add(fileName);
        if (logger.isDebugEnabled()) {
            logger.debug("Registered merged segment: {}", fileName);
        }
    }

    /**
     * Check if a file is a merged segment
     * @param fileName Name of the file to check
     * @return true if the file is a registered merged segment
     */
    public boolean isMergedSegment(String fileName) {
        return mergedSegments.contains(fileName);
    }

    /**
     * Unregister a merged segment file
     * @param fileName Name of the merged segment file to unregister
     */
    public void unregisterMergedSegment(String fileName) {
        if (mergedSegments.remove(fileName) && logger.isDebugEnabled()) {
            logger.debug("Unregistered merged segment: {}", fileName);
        }
    }

    /**
     * Get current registry size
     * @return Number of entries in registry
     */
    public int size() {
        return mergedSegments.size();
    }

    public Set<String> get() {
        return Collections.unmodifiableSet(this.mergedSegments);
    }
}
