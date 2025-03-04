/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.seatunnel.connectors.seatunnel.paimon.sink;

import org.apache.seatunnel.api.common.JobContext;
import org.apache.seatunnel.api.sink.SinkWriter;
import org.apache.seatunnel.api.sink.SupportMultiTableSinkWriter;
import org.apache.seatunnel.api.table.type.SeaTunnelRow;
import org.apache.seatunnel.api.table.type.SeaTunnelRowType;
import org.apache.seatunnel.common.utils.SeaTunnelException;
import org.apache.seatunnel.connectors.seatunnel.paimon.config.PaimonHadoopConfiguration;
import org.apache.seatunnel.connectors.seatunnel.paimon.config.PaimonSinkConfig;
import org.apache.seatunnel.connectors.seatunnel.paimon.exception.PaimonConnectorErrorCode;
import org.apache.seatunnel.connectors.seatunnel.paimon.exception.PaimonConnectorException;
import org.apache.seatunnel.connectors.seatunnel.paimon.security.PaimonSecurityContext;
import org.apache.seatunnel.connectors.seatunnel.paimon.sink.bucket.PaimonBucketAssigner;
import org.apache.seatunnel.connectors.seatunnel.paimon.sink.commit.PaimonCommitInfo;
import org.apache.seatunnel.connectors.seatunnel.paimon.sink.state.PaimonSinkState;
import org.apache.seatunnel.connectors.seatunnel.paimon.utils.JobContextUtil;
import org.apache.seatunnel.connectors.seatunnel.paimon.utils.RowConverter;

import org.apache.paimon.CoreOptions;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.disk.IOManager;
import org.apache.paimon.schema.TableSchema;
import org.apache.paimon.table.BucketMode;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.Table;
import org.apache.paimon.table.sink.BatchTableCommit;
import org.apache.paimon.table.sink.BatchTableWrite;
import org.apache.paimon.table.sink.CommitMessage;
import org.apache.paimon.table.sink.StreamTableCommit;
import org.apache.paimon.table.sink.StreamTableWrite;
import org.apache.paimon.table.sink.TableCommit;
import org.apache.paimon.table.sink.TableWrite;
import org.apache.paimon.table.sink.WriteBuilder;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.apache.paimon.disk.IOManagerImpl.splitPaths;

@Slf4j
public class PaimonSinkWriter
        implements SinkWriter<SeaTunnelRow, PaimonCommitInfo, PaimonSinkState>,
                SupportMultiTableSinkWriter<Void> {

    private String commitUser = UUID.randomUUID().toString();

    private final FileStoreTable table;

    private final WriteBuilder tableWriteBuilder;

    private final TableWrite tableWrite;

    private List<CommitMessage> committables = new ArrayList<>();

    private final SeaTunnelRowType seaTunnelRowType;

    private final SinkWriter.Context context;

    private final JobContext jobContext;

    private final TableSchema tableSchema;

    private PaimonBucketAssigner bucketAssigner;

    private final boolean dynamicBucket;

    public PaimonSinkWriter(
            Context context,
            Table table,
            SeaTunnelRowType seaTunnelRowType,
            JobContext jobContext,
            PaimonSinkConfig paimonSinkConfig,
            PaimonHadoopConfiguration paimonHadoopConfiguration) {
        this.table = (FileStoreTable) table;
        CoreOptions.ChangelogProducer changelogProducer =
                this.table.coreOptions().changelogProducer();
        if (Objects.nonNull(paimonSinkConfig.getChangelogProducer())
                && changelogProducer != paimonSinkConfig.getChangelogProducer()) {
            log.warn(
                    "configured the props named 'changelog-producer' which is not compatible with the options in table , so it will use the table's 'changelog-producer'");
        }
        String changelogTmpPath = paimonSinkConfig.getChangelogTmpPath();
        this.tableWriteBuilder =
                JobContextUtil.isBatchJob(jobContext)
                        ? this.table.newBatchWriteBuilder()
                        : this.table.newStreamWriteBuilder();
        this.tableWrite =
                tableWriteBuilder
                        .newWrite()
                        .withIOManager(IOManager.create(splitPaths(changelogTmpPath)));
        this.seaTunnelRowType = seaTunnelRowType;
        this.context = context;
        this.jobContext = jobContext;
        this.tableSchema = this.table.schema();
        BucketMode bucketMode = this.table.bucketMode();
        this.dynamicBucket =
                BucketMode.HASH_DYNAMIC == bucketMode || BucketMode.CROSS_PARTITION == bucketMode;
        int bucket = ((FileStoreTable) table).coreOptions().bucket();
        if (bucket == -1 && BucketMode.BUCKET_UNAWARE == bucketMode) {
            log.warn("Append only table currently do not support dynamic bucket");
        }
        if (dynamicBucket) {
            this.bucketAssigner =
                    new PaimonBucketAssigner(
                            table,
                            this.context.getNumberOfParallelSubtasks(),
                            this.context.getIndexOfSubtask());
        }
        PaimonSecurityContext.shouldEnableKerberos(paimonHadoopConfiguration);
    }

    public PaimonSinkWriter(
            Context context,
            Table table,
            SeaTunnelRowType seaTunnelRowType,
            List<PaimonSinkState> states,
            JobContext jobContext,
            PaimonSinkConfig paimonSinkConfig,
            PaimonHadoopConfiguration paimonHadoopConfiguration) {
        this(
                context,
                table,
                seaTunnelRowType,
                jobContext,
                paimonSinkConfig,
                paimonHadoopConfiguration);
        if (Objects.isNull(states) || states.isEmpty()) {
            return;
        }
        this.commitUser = states.get(0).getCommitUser();
        long checkpointId = states.get(0).getCheckpointId();
        try (TableCommit tableCommit = tableWriteBuilder.newCommit()) {
            List<CommitMessage> commitables =
                    states.stream()
                            .map(PaimonSinkState::getCommittables)
                            .flatMap(List::stream)
                            .collect(Collectors.toList());
            log.info("Trying to recommit states {}", commitables);
            if (JobContextUtil.isBatchJob(jobContext)) {
                log.debug("Trying to recommit states batch mode");
                ((BatchTableCommit) tableCommit).commit(commitables);
            } else {
                log.debug("Trying to recommit states streaming mode");
                ((StreamTableCommit) tableCommit).commit(checkpointId, commitables);
            }
        } catch (Exception e) {
            throw new PaimonConnectorException(
                    PaimonConnectorErrorCode.TABLE_WRITE_COMMIT_FAILED, e);
        }
    }

    @Override
    public void write(SeaTunnelRow element) throws IOException {
        InternalRow rowData = RowConverter.reconvert(element, seaTunnelRowType, tableSchema);
        try {
            PaimonSecurityContext.runSecured(
                    () -> {
                        if (dynamicBucket) {
                            int bucket = bucketAssigner.assign(rowData);
                            tableWrite.write(rowData, bucket);
                        } else {
                            tableWrite.write(rowData);
                        }
                        return null;
                    });
        } catch (Exception e) {
            throw new PaimonConnectorException(
                    PaimonConnectorErrorCode.TABLE_WRITE_RECORD_FAILED,
                    "This record " + element + " failed to be written",
                    e);
        }
    }

    @Override
    public Optional<PaimonCommitInfo> prepareCommit() throws IOException {
        return Optional.empty();
    }

    @Override
    public Optional<PaimonCommitInfo> prepareCommit(long checkpointId) throws IOException {
        try {
            List<CommitMessage> fileCommittables;
            if (JobContextUtil.isBatchJob(jobContext)) {
                fileCommittables = ((BatchTableWrite) tableWrite).prepareCommit();
            } else {
                fileCommittables =
                        ((StreamTableWrite) tableWrite)
                                .prepareCommit(waitCompaction(), checkpointId);
            }
            committables.addAll(fileCommittables);
            return Optional.of(new PaimonCommitInfo(fileCommittables, checkpointId));
        } catch (Exception e) {
            throw new PaimonConnectorException(
                    PaimonConnectorErrorCode.TABLE_PRE_COMMIT_FAILED,
                    "Paimon pre-commit failed.",
                    e);
        }
    }

    @Override
    public List<PaimonSinkState> snapshotState(long checkpointId) throws IOException {
        PaimonSinkState paimonSinkState =
                new PaimonSinkState(new ArrayList<>(committables), commitUser, checkpointId);
        committables.clear();
        return Collections.singletonList(paimonSinkState);
    }

    @Override
    public void abortPrepare() {}

    @Override
    public void close() throws IOException {
        try {
            if (Objects.nonNull(tableWrite)) {
                try {
                    tableWrite.close();
                } catch (Exception e) {
                    log.error("Failed to close table writer in paimon sink writer.", e);
                    throw new SeaTunnelException(e);
                }
            }
        } finally {
            committables.clear();
        }
    }

    private boolean waitCompaction() {
        CoreOptions.ChangelogProducer changelogProducer =
                this.table.coreOptions().changelogProducer();
        return changelogProducer == CoreOptions.ChangelogProducer.LOOKUP
                || changelogProducer == CoreOptions.ChangelogProducer.FULL_COMPACTION;
    }
}
