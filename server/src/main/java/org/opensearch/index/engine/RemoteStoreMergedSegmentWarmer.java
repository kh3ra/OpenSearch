/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

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

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.index.engine;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.lucene.index.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.util.Version;
import org.opensearch.action.ActionListenerResponseHandler;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.transport.TransportResponse;
import org.opensearch.index.MergedSegmentRegistry;
import org.opensearch.index.seqno.SequenceNumbers;
import org.opensearch.index.shard.IndexShard;
import org.opensearch.index.store.RemoteSegmentStoreDirectory;
import org.opensearch.index.store.RemoteSegmentStoreDirectory.UploadedSegmentMetadata;
import org.opensearch.index.translog.Translog;
import org.opensearch.indices.recovery.RecoverySettings;
import org.opensearch.indices.replication.SegmentReplicationTargetService;
import org.opensearch.indices.replication.checkpoint.PublishMergedSegmentRequest;
import org.opensearch.indices.replication.checkpoint.ReplicationCheckpoint;
import org.opensearch.indices.replication.checkpoint.ReplicationSegmentCheckpoint;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.TransportService;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Implementation of a {@link IndexWriter.IndexReaderWarmer} when local on-disk segment replication is enabled.
 *
 * @opensearch.internal
 */
public class RemoteStoreMergedSegmentWarmer implements IndexWriter.IndexReaderWarmer {
    private static final Logger logger = LogManager.getLogger(RemoteStoreMergedSegmentWarmer.class);
    private final TransportService transportService;
    private final RecoverySettings recoverySettings;
    private final ClusterService clusterService;
    private final IndexShard indexShard;

    public RemoteStoreMergedSegmentWarmer(
        TransportService transportService,
        RecoverySettings recoverySettings,
        ClusterService clusterService,
        IndexShard indexShard
    ) {
        this.transportService = transportService;
        this.recoverySettings = recoverySettings;
        this.clusterService = clusterService;
        this.indexShard = indexShard;
    }

    @Override
    public void warm(LeafReader leafReader) throws IOException {
        logger.info("WARM CALLED {}", Thread.currentThread().getName());
        SegmentCommitInfo segmentCommitInfo = ((SegmentReader) leafReader).getSegmentInfo();
        ReplicationSegmentCheckpoint mergedSegment = indexShard.computeReplicationSegmentCheckpoint(segmentCommitInfo);

        List<DiscoveryNode> activeReplicaNodes = indexShard.getActiveReplicaNodes();
        if (activeReplicaNodes.isEmpty()) {
            logger.trace("There are no active replicas, skip pre copy merged segment [{}]", segmentCommitInfo.info.name);
            return;
        }

        logger.info("#### Updating registry");
        final MergedSegmentRegistry registry = MergedSegmentRegistry.getInstance();
        segmentCommitInfo.files().forEach(registry::registerMergedSegment);
        logger.info("#### Updated registry");


        logger.info("#### uploading segment files.");
        Map<String, UploadedSegmentMetadata> uploadedSegments = uploadNewSegments(
            segmentCommitInfo.files(),
            segmentCommitInfo.info.getVersion(),
            mergedSegment
        );
        logger.info("#### uploading segment files complete.");

        logger.info("????? SegmentCommitInfo [{}]", segmentCommitInfo.info);
        logger.info("#### uploading metadata files - VERSIONS");
        segmentCommitInfo.info.files().forEach(file -> {logger.info("File {} | Version {}", file, segmentCommitInfo.info.getVersion().major);});
        SegmentInfos segmentInfosSnapshot = new SegmentInfos(segmentCommitInfo.info.getVersion().major);
        segmentInfosSnapshot.add(segmentCommitInfo);
        List<String> uploadedSegmentsList = uploadedSegments.keySet().stream().toList();
        indexShard.getRemoteDirectory().uploadMergedSegmentMetadata(
            uploadedSegmentsList,
            segmentInfosSnapshot,
            indexShard.store().directory(),
            indexShard.computeReplicationSegmentCheckpoint(segmentCommitInfo),
            indexShard.getNodeId() // required?
        );
        logger.info("#### uploading metadata files complete.");

        logger.info("#### notifying replicas and waiting for response.");
        notifyReplicas(activeReplicaNodes, mergedSegment, segmentCommitInfo);
        logger.info("#### warm complete.");
        indexShard.getRemoteDirectory().moveMergedSegmentsToSegmentsUploadedToRemoteStore(
            uploadedSegmentsList
        );
        uploadedSegmentsList.forEach(segment -> {
            logger.info("Unregistering {}", segment);
            MergedSegmentRegistry.getInstance().unregisterMergedSegment(segment);
        });
    }

    private void notifyReplicas(List<DiscoveryNode> activeReplicaNodes, ReplicationSegmentCheckpoint checkpoint, SegmentCommitInfo segmentCommitInfo) {
        PublishMergedSegmentRequest request = new PublishMergedSegmentRequest(checkpoint);

        CountDownLatch countDownLatch = new CountDownLatch(activeReplicaNodes.size());
        AtomicInteger successfulCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        for (DiscoveryNode replicaNode : activeReplicaNodes) {
            ActionListener<TransportResponse> listener = ActionListener.wrap(r -> {
                successfulCount.incrementAndGet();
                countDownLatch.countDown();
            }, e -> {
                failureCount.incrementAndGet();
                countDownLatch.countDown();
            });
            transportService.sendRequest(
                replicaNode,
                SegmentReplicationTargetService.Actions.PUBLISH_MERGED_SEGMENT,
                request,
                new ActionListenerResponseHandler<>(listener, (in) -> TransportResponse.Empty.INSTANCE, ThreadPool.Names.GENERIC)
            );
        }
        try {
            countDownLatch.await(recoverySettings.getMergedSegmentReplicationTimeout().seconds(), TimeUnit.SECONDS);
            logger.trace(
                "pre copy merged segment [{}] to [{}] active replicas, [{}] successful, [{}] failed",
                segmentCommitInfo.info.name,
                activeReplicaNodes.size(),
                successfulCount,
                failureCount
            );
        } catch (InterruptedException e) {
            logger.warn(
                () -> new ParameterizedMessage("Interrupted while waiting for pre copy merged segment [{}]", segmentCommitInfo.info.name),
                e
            );
        }
    }

    Map<String, UploadedSegmentMetadata> uploadNewSegments(
        Collection<String> localSegmentsPostMerge,
        Version version,
        ReplicationSegmentCheckpoint checkpoint
    ) {
        RemoteSegmentStoreDirectory remoteSegmentStoreDirectory = indexShard.getRemoteDirectory();
        Directory storeDirectory = indexShard.store().directory();
        Map<String, UploadedSegmentMetadata> uploadedSegmentMetadata = new HashMap<>();
        ActionListener<Void> aggregatedListener = ActionListener.wrap(resp -> {
            logger.info("@ Listener: " + resp);
        }, ex -> {
            logger.warn(() -> new ParameterizedMessage("Exception: [{}] while uploading segment files for shard {}", ex, indexShard.shardId()), ex);
            if (ex instanceof CorruptIndexException) {
                indexShard.failShard(ex.getMessage(), ex);
            }
        });

        localSegmentsPostMerge.forEach(segment -> {
            logger.debug(" Copying over segment {} to remote store", segment);
            remoteSegmentStoreDirectory.copyFrom(storeDirectory, segment, IOContext.DEFAULT, aggregatedListener, true);
            UploadedSegmentMetadata metadata = remoteSegmentStoreDirectory.getMergedSegmentsUploadedToRemoteStore().get(segment);
            metadata.setWrittenByMajor(version.major);
            uploadedSegmentMetadata.put(segment, metadata);
        });


        return uploadedSegmentMetadata;
    }
}
