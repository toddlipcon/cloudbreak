package com.sequenceiq.datalake.service.sdx.poller;

import java.util.function.Function;

import javax.ws.rs.NotFoundException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dyngr.core.AttemptMaker;
import com.dyngr.core.AttemptResult;
import com.dyngr.core.AttemptResults;
import com.sequenceiq.cloudbreak.cloud.scheduler.PollGroup;
import com.sequenceiq.cloudbreak.common.json.JsonUtil;
import com.sequenceiq.cloudbreak.logger.MDCBuilder;
import com.sequenceiq.datalake.entity.SdxCluster;
import com.sequenceiq.datalake.flow.statestore.DatalakeInMemoryStateStore;
import com.sequenceiq.datalake.service.sdx.SdxDatabaseOperation;
import com.sequenceiq.datalake.service.sdx.SdxNotificationService;
import com.sequenceiq.redbeams.api.endpoint.v4.databaseserver.responses.DatabaseServerStatusV4Response;

public class DatabaseOperationPollingAttempMaker implements AttemptMaker<DatabaseServerStatusV4Response> {

    private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseOperationPollingAttempMaker.class);

    private boolean cancellable;

    private SdxCluster sdxCluster;

    private String requestId;

    private SdxDatabaseOperation sdxDatabaseOperation;

    private SdxNotificationService notificationService;

    private Function<String, DatabaseServerStatusV4Response> databaseStatusFunction;

    private String databaseCrn;

    public static class DatabaseOperationPollingAttempMakerBuilder {

        private DatabaseOperationPollingAttempMaker attemptMaker;

        public DatabaseOperationPollingAttempMakerBuilder() {
            attemptMaker = new DatabaseOperationPollingAttempMaker();
        }

        public DatabaseOperationPollingAttempMakerBuilder notificationService(SdxNotificationService notificationService) {
            attemptMaker.setNotificationService(notificationService);
            return this;
        }

        public DatabaseOperationPollingAttempMakerBuilder cancellable(boolean cancellable) {
            attemptMaker.setCancellable(cancellable);
            return this;
        }

        public DatabaseOperationPollingAttempMakerBuilder requestId(String requestId) {
            attemptMaker.setRequestId(requestId);
            return this;
        }

        public DatabaseOperationPollingAttempMakerBuilder sdxCluster(SdxCluster sdxCluster) {
            attemptMaker.setSdxCluster(sdxCluster);
            return this;
        }

        public DatabaseOperationPollingAttempMakerBuilder sdxDatabaseOperation(SdxDatabaseOperation sdxDatabaseOperation) {
            attemptMaker.setSdxDatabaseOperation(sdxDatabaseOperation);
            return this;
        }

        public DatabaseOperationPollingAttempMakerBuilder databaseCrn(String databaseCrn) {
            attemptMaker.setDatabaseCrn(databaseCrn);
            return this;
        }

        public DatabaseOperationPollingAttempMakerBuilder databaseStatusFunction(Function<String, DatabaseServerStatusV4Response> databaseStatusFunction) {
            attemptMaker.setDatabaseStatusFunction(databaseStatusFunction);
            return this;
        }

        public DatabaseOperationPollingAttempMaker build() {
            return attemptMaker;
        }
    }

    @Override
    public AttemptResult process() throws Exception {
        if (cancellable && PollGroup.CANCELLED.equals(DatalakeInMemoryStateStore.get(sdxCluster.getId()))) {
            LOGGER.info("Database wait polling cancelled in inmemory store, id: " + sdxCluster.getId());
            return AttemptResults.breakFor("Database wait polling cancelled in inmemory store, id: " + sdxCluster.getId());
        }
        try {
            MDCBuilder.addRequestIdToMdcContext(requestId);
            LOGGER.info("Creation polling redbeams for database status: '{}' in '{}' env",
                    sdxCluster.getClusterName(), sdxCluster.getEnvName());
            DatabaseServerStatusV4Response rdsStatus = databaseStatusFunction.apply(databaseCrn);
            LOGGER.info("Response from redbeams: {}", JsonUtil.writeValueAsString(rdsStatus));
            if (sdxDatabaseOperation.getExitCriteria().apply(rdsStatus.getStatus())) {
                notificationService.send(sdxDatabaseOperation.getFinishedEvent(), sdxCluster);
                return AttemptResults.finishWith(rdsStatus);
            } else {
                if (sdxDatabaseOperation.getFailureCriteria().apply(rdsStatus.getStatus())) {
                    notificationService.send(sdxDatabaseOperation.getFailedEvent(), sdxCluster);
                    if (rdsStatus.getStatusReason() != null && rdsStatus.getStatusReason().contains("does not exist")) {
                        return AttemptResults.finishWith(null);
                    }
                    return AttemptResults.breakFor("Database operation failed " + sdxCluster.getEnvName()
                            + " statusReason: " + rdsStatus.getStatusReason());
                } else {
                    return AttemptResults.justContinue();
                }
            }
        } catch (NotFoundException e) {
            notificationService.send(sdxDatabaseOperation.getFinishedEvent(), sdxCluster);
            return AttemptResults.finishWith(null);
        }
    }

    public void setCancellable(boolean cancellable) {
        this.cancellable = cancellable;
    }

    public void setSdxCluster(SdxCluster sdxCluster) {
        this.sdxCluster = sdxCluster;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public void setSdxDatabaseOperation(SdxDatabaseOperation sdxDatabaseOperation) {
        this.sdxDatabaseOperation = sdxDatabaseOperation;
    }

    public void setNotificationService(SdxNotificationService notificationService) {
        this.notificationService = notificationService;
    }

    public void setDatabaseStatusFunction(Function<String, DatabaseServerStatusV4Response> databaseStatusFunction) {
        this.databaseStatusFunction = databaseStatusFunction;
    }

    public void setDatabaseCrn(String databaseCrn) {
        this.databaseCrn = databaseCrn;
    }
}
