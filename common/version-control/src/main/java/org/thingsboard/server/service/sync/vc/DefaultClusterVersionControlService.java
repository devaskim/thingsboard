/**
 * Copyright © 2016-2022 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.service.sync.vc;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.thingsboard.common.util.ThingsBoardThreadFactory;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.sync.vc.EntitiesVersionControlSettings;
import org.thingsboard.server.common.data.sync.vc.VersionCreationResult;
import org.thingsboard.server.common.msg.queue.ServiceType;
import org.thingsboard.server.common.msg.queue.TopicPartitionInfo;
import org.thingsboard.server.gen.transport.TransportProtos;
import org.thingsboard.server.gen.transport.TransportProtos.*;
import org.thingsboard.server.gen.transport.TransportProtos.ToCoreNotificationMsg;
import org.thingsboard.server.gen.transport.TransportProtos.ToVersionControlServiceMsg;
import org.thingsboard.server.queue.TbQueueConsumer;
import org.thingsboard.server.queue.TbQueueProducer;
import org.thingsboard.server.queue.common.TbProtoQueueMsg;
import org.thingsboard.server.queue.discovery.NotificationsTopicService;
import org.thingsboard.server.queue.discovery.TbApplicationEventListener;
import org.thingsboard.server.queue.discovery.TbServiceInfoProvider;
import org.thingsboard.server.queue.discovery.event.PartitionChangeEvent;
import org.thingsboard.server.queue.provider.TbVersionControlQueueFactory;
import org.thingsboard.server.queue.util.DataDecodingEncodingService;
import org.thingsboard.server.queue.util.TbVersionControlComponent;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

@Slf4j
@TbVersionControlComponent
@Service
@RequiredArgsConstructor
public class DefaultClusterVersionControlService extends TbApplicationEventListener<PartitionChangeEvent> implements ClusterVersionControlService {

    private final TbServiceInfoProvider serviceInfoProvider;
    private final TbVersionControlQueueFactory queueFactory;
    private final DataDecodingEncodingService encodingService;
    private final GitRepositoryService vcService;
    private final NotificationsTopicService notificationsTopicService;

    private final ConcurrentMap<TenantId, Lock> tenantRepoLocks = new ConcurrentHashMap<>();
    private final Map<TenantId, PendingCommit> pendingCommitMap = new HashMap<>();

    private volatile ExecutorService consumerExecutor;
    private volatile TbQueueConsumer<TbProtoQueueMsg<ToVersionControlServiceMsg>> consumer;
    private volatile TbQueueProducer<TbProtoQueueMsg<ToCoreNotificationMsg>> producer;
    private volatile boolean stopped = false;

    @Value("${queue.vc.poll-interval:25}")
    private long pollDuration;
    @Value("${queue.vc.pack-processing-timeout:60000}")
    private long packProcessingTimeout;

    @PostConstruct
    public void init() {
        consumerExecutor = Executors.newSingleThreadExecutor(ThingsBoardThreadFactory.forName("vc-consumer"));
        producer = queueFactory.createTbCoreNotificationsMsgProducer();
        consumer = queueFactory.createToVersionControlMsgConsumer();
    }

    @PreDestroy
    public void stop() {
        stopped = true;
        if (consumer != null) {
            consumer.unsubscribe();
        }
        if (consumerExecutor != null) {
            consumerExecutor.shutdownNow();
        }
    }

    @Override
    protected void onTbApplicationEvent(PartitionChangeEvent event) {
        //TODO: cleanup repositories that we no longer manage in this node.
        consumer.subscribe(event.getPartitions());
    }

    @Override
    protected boolean filterTbApplicationEvent(PartitionChangeEvent event) {
        return ServiceType.TB_VC_EXECUTOR.equals(event.getServiceType());
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(value = 2)
    public void onApplicationEvent(ApplicationReadyEvent event) {
        consumerExecutor.execute(() -> consumerLoop(consumer));
    }

    void consumerLoop(TbQueueConsumer<TbProtoQueueMsg<ToVersionControlServiceMsg>> consumer) {
        while (!stopped && !consumer.isStopped()) {
            try {
                List<TbProtoQueueMsg<ToVersionControlServiceMsg>> msgs = consumer.poll(pollDuration);
                if (msgs.isEmpty()) {
                    continue;
                }
                for (TbProtoQueueMsg<ToVersionControlServiceMsg> msgWrapper : msgs) {
                    ToVersionControlServiceMsg msg = msgWrapper.getValue();
                    var ctx = new VersionControlRequestCtx(msg, msg.hasClearRepositoryRequest() ? null : getEntitiesVersionControlSettings(msg));
                    var lock = getRepoLock(ctx.getTenantId());
                    lock.lock();
                    try {
                        if (msg.hasClearRepositoryRequest()) {
                            handleClearRepositoryCommand(ctx);
                        } else {
                            if (msg.hasTestRepositoryRequest()) {
                                handleTestRepositoryCommand(ctx);
                            } else if (msg.hasInitRepositoryRequest()) {
                                handleInitRepositoryCommand(ctx);
                            } else {
                                var currentSettings = vcService.getRepositorySettings(ctx.getTenantId());
                                var newSettings = ctx.getSettings();
                                if (!newSettings.equals(currentSettings)) {
                                    vcService.initRepository(ctx.getTenantId(), ctx.getSettings());
                                }
                                if (msg.hasCommitRequest()) {
                                    handleCommitRequest(ctx, msg.getCommitRequest());
                                }
                            }
                        }
                    } catch (Exception e) {
                        reply(ctx, Optional.of(e));
                    } finally {
                        lock.unlock();
                    }
                }
                //TODO: handle timeouts and async processing for multiple tenants;
                consumer.commit();
            } catch (Exception e) {
                if (!stopped) {
                    log.warn("Failed to obtain version control requests from queue.", e);
                    try {
                        Thread.sleep(pollDuration);
                    } catch (InterruptedException e2) {
                        log.trace("Failed to wait until the server has capacity to handle new version control messages", e2);
                    }
                }
            }
        }
        log.info("TB Version Control request consumer stopped.");
    }

    private void handleCommitRequest(VersionControlRequestCtx ctx, CommitRequestMsg commitRequest) throws Exception {
        var tenantId = ctx.getTenantId();
        UUID txId = UUID.fromString(commitRequest.getTxId());
        if (commitRequest.hasPrepareMsg()) {
            prepareCommit(ctx, txId, commitRequest.getPrepareMsg());
        } else if (commitRequest.hasAbortMsg()) {
            PendingCommit current = pendingCommitMap.get(tenantId);
            if (current != null && current.getTxId().equals(txId)) {
                doAbortCurrentCommit(tenantId, current);
            }
        } else {
            PendingCommit current = pendingCommitMap.get(tenantId);
            if (current != null && current.getTxId().equals(txId)) {
                try {
                    if (commitRequest.hasAddMsg()) {
                        addToCommit(ctx, current, commitRequest.getAddMsg());
                    } else if (commitRequest.hasDeleteMsg()) {
                        deleteFromCommit(ctx, current, commitRequest.getDeleteMsg());
                    } else if (commitRequest.hasPushMsg()) {
                        reply(ctx, vcService.push(current));
                    }
                } catch (Exception e) {
                    doAbortCurrentCommit(tenantId, current, e);
                    throw e;
                }
            } else {
                log.debug("[{}] Ignore request due to stale commit: {}", txId, commitRequest);
            }
        }
    }

    private void prepareCommit(VersionControlRequestCtx ctx, UUID txId, PrepareMsg prepareMsg) {
        var tenantId = ctx.getTenantId();
        var pendingCommit = new PendingCommit(tenantId, ctx.getNodeId(), txId, prepareMsg.getBranchName(), prepareMsg.getCommitMsg());
        PendingCommit old = pendingCommitMap.get(tenantId);
        if (old != null) {
            doAbortCurrentCommit(tenantId, old);
        }
        pendingCommitMap.put(tenantId, pendingCommit);
        vcService.prepareCommit(pendingCommit);
    }

    private void deleteFromCommit(VersionControlRequestCtx ctx, PendingCommit commit, DeleteMsg deleteMsg) throws IOException {
        vcService.deleteFolderContent(commit, deleteMsg.getRelativePath());
    }

    private void addToCommit(VersionControlRequestCtx ctx, PendingCommit commit, AddMsg addMsg) throws IOException {
        vcService.add(commit, addMsg.getRelativePath(), addMsg.getEntityDataJson());
    }

    private void doAbortCurrentCommit(TenantId tenantId, PendingCommit current) {
        doAbortCurrentCommit(tenantId, current, null);
    }

    private void doAbortCurrentCommit(TenantId tenantId, PendingCommit current, Exception e) {
        vcService.abort(current);
        pendingCommitMap.remove(tenantId);
        //TODO: push notification to core using old.getNodeId() to cancel old commit processing on the caller side.
    }

    private void handleClearRepositoryCommand(VersionControlRequestCtx ctx) {
        try {
            vcService.clearRepository(ctx.getTenantId());
            reply(ctx, Optional.empty());
        } catch (Exception e) {
            log.debug("[{}] Failed to connect to the repository: ", ctx, e);
            reply(ctx, Optional.of(e));
        }
    }

    private void handleInitRepositoryCommand(VersionControlRequestCtx ctx) {
        try {
            vcService.initRepository(ctx.getTenantId(), ctx.getSettings());
            reply(ctx, Optional.empty());
        } catch (Exception e) {
            log.debug("[{}] Failed to connect to the repository: ", ctx, e);
            reply(ctx, Optional.of(e));
        }
    }


    private void handleTestRepositoryCommand(VersionControlRequestCtx ctx) {
        try {
            vcService.testRepository(ctx.getTenantId(), ctx.getSettings());
            reply(ctx, Optional.empty());
        } catch (Exception e) {
            log.debug("[{}] Failed to connect to the repository: ", ctx, e);
            reply(ctx, Optional.of(e));
        }
    }

    private void reply(VersionControlRequestCtx ctx, VersionCreationResult result) {
        reply(ctx, Optional.empty(), builder -> builder.setCommitResponse(CommitResponseMsg.newBuilder()
                .setCommitId(result.getVersion().getId())
                .setName(result.getVersion().getName())
                .setAdded(result.getAdded())
                .setModified(result.getModified())
                .setRemoved(result.getRemoved())));
    }

    private void reply(VersionControlRequestCtx ctx, Optional<Exception> e) {
        reply(ctx, e, null);
    }

    private void reply(VersionControlRequestCtx ctx, Optional<Exception> e, Function<VersionControlResponseMsg.Builder, VersionControlResponseMsg.Builder> enrichFunction) {
        TopicPartitionInfo tpi = notificationsTopicService.getNotificationsTopic(ServiceType.TB_CORE, ctx.getNodeId());
        VersionControlResponseMsg.Builder builder = VersionControlResponseMsg.newBuilder()
                .setRequestIdMSB(ctx.getRequestId().getMostSignificantBits())
                .setRequestIdLSB(ctx.getRequestId().getLeastSignificantBits());
        if (e.isPresent()) {
            builder.setError(e.get().getMessage());
        }
        if (enrichFunction != null) {
            builder = enrichFunction.apply(builder);
        } else {
            builder.setGenericResponse(TransportProtos.GenericRepositoryResponseMsg.newBuilder().build());
        }
        ToCoreNotificationMsg msg = ToCoreNotificationMsg.newBuilder().setVcResponseMsg(builder).build();
        log.trace("PUSHING msg: {} to: {}", msg, tpi);
        producer.send(tpi, new TbProtoQueueMsg<>(UUID.randomUUID(), msg), null);
    }

    private EntitiesVersionControlSettings getEntitiesVersionControlSettings(ToVersionControlServiceMsg msg) {
        Optional<EntitiesVersionControlSettings> settingsOpt = encodingService.decode(msg.getVcSettings().toByteArray());
        if (settingsOpt.isPresent()) {
            return settingsOpt.get();
        } else {
            log.warn("Failed to parse VC settings: {}", msg.getVcSettings());
            throw new RuntimeException("Failed to parse vc settings!");
        }
    }

    private Lock getRepoLock(TenantId tenantId) {
        return tenantRepoLocks.computeIfAbsent(tenantId, t -> new ReentrantLock(true));
    }


}
