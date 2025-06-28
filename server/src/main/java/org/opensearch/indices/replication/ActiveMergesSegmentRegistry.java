/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.indices.replication;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import reactor.util.annotation.NonNull;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TODO@kheraadi: Simplify this
 * Usages:
 * 1. Used by GC to exclude merged segment files for deletion
 * 2. Used to track remote store segment file names (remove this)
 *
 */
public class ActiveMergesSegmentRegistry {
    private final Map<String, String> myMergedSegments = new ConcurrentHashMap<>();
    private static final String PENDING_REMOTE_FILE_NAME = "PENDING_REMOTE_FILE_NAME";

    private static class HOLDER {
        private static final ActiveMergesSegmentRegistry INSTANCE = new ActiveMergesSegmentRegistry();
    }

    private ActiveMergesSegmentRegistry() {};

    public static ActiveMergesSegmentRegistry getInstance() {
        return HOLDER.INSTANCE;
    }

    public void updateRemoteSegmentFileName(@NonNull String localSegmentFileName, @NonNull String remoteSegmentFileName) {
        if (contains(localSegmentFileName) && PENDING_REMOTE_FILE_NAME.equals(getExistingRemoteSegmentFileName(localSegmentFileName)) == false){
            // This should never happen
            if (remoteSegmentFileName.equals(getExistingRemoteSegmentFileName(localSegmentFileName))) {
                return;
            }
            throw new IllegalArgumentException("Segment " + localSegmentFileName + " is already registered as " + getExistingRemoteSegmentFileName(localSegmentFileName) + ". Called with " + remoteSegmentFileName);
        }
        myMergedSegments.put(localSegmentFileName, remoteSegmentFileName);
    }

    public void register(@NonNull String localSegmentFileName) {
        if (contains(localSegmentFileName)){
            if(PENDING_REMOTE_FILE_NAME.equals(getExistingRemoteSegmentFileName(localSegmentFileName))) {
                return;
            }
            throw new IllegalArgumentException(localSegmentFileName + ": " + getExistingRemoteSegmentFileName(localSegmentFileName) + " already registered. Cannot reregister.");
        }

        myMergedSegments.put(localSegmentFileName, PENDING_REMOTE_FILE_NAME);
    }

    public void unregister(@NonNull String segmentFileName) {
        myMergedSegments.remove(segmentFileName);
    }

    public boolean contains(@NonNull String segmentFileName) {
        return myMergedSegments.containsKey(segmentFileName);
    }

    public String getExistingRemoteSegmentFileName(@NonNull String localSegmentFileName) {
        if (contains(localSegmentFileName) == false) {
            // This should never happen
            throw new IllegalArgumentException("Segment " + localSegmentFileName + " is not registered");
        }

        return myMergedSegments.get(localSegmentFileName);
    }

    public boolean canDelete(@NonNull String segmentFileName) {
        String originalFileName = getOriginalFileName(segmentFileName);
        return contains(originalFileName) &&
            segmentFileName.equals(getExistingRemoteSegmentFileName(originalFileName));
    }

    private String getOriginalFileName(@NonNull String remoteSegmentFileName) {
        String originalFileName = remoteSegmentFileName.split("__")[0];
        return originalFileName;
    }
}
