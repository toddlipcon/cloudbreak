package com.sequenceiq.datalake.service.sdx.poller;

import java.util.Collections;

import javax.ws.rs.NotFoundException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dyngr.core.AttemptMaker;
import com.dyngr.core.AttemptResult;
import com.dyngr.core.AttemptResults;
import com.sequenceiq.cloudbreak.api.endpoint.v4.common.Status;
import com.sequenceiq.cloudbreak.api.endpoint.v4.stacks.response.StackV4Response;
import com.sequenceiq.cloudbreak.client.CloudbreakServiceUserCrnClient;
import com.sequenceiq.cloudbreak.common.json.JsonUtil;
import com.sequenceiq.cloudbreak.event.ResourceEvent;
import com.sequenceiq.cloudbreak.logger.MDCBuilder;
import com.sequenceiq.datalake.entity.SdxCluster;
import com.sequenceiq.datalake.service.sdx.SdxNotificationService;

public class ClusterDeletionPollingAttemptMaker implements AttemptMaker {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClusterDeletionPollingAttemptMaker.class);

    private SdxNotificationService notificationService;

    private CloudbreakServiceUserCrnClient cloudbreakClient;

    private SdxCluster sdxCluster;

    private String requestId;

    public static class ClusterDeletionPollingAttemptMakerBuilder {

        private ClusterDeletionPollingAttemptMaker attemptMaker;

        public ClusterDeletionPollingAttemptMakerBuilder() {
            attemptMaker = new ClusterDeletionPollingAttemptMaker();
        }

        public ClusterDeletionPollingAttemptMakerBuilder notificationService(SdxNotificationService notificationService) {
            attemptMaker.setNotificationService(notificationService);
            return this;
        }

        public ClusterDeletionPollingAttemptMakerBuilder cloudbreakClient(CloudbreakServiceUserCrnClient cloudbreakClient) {
            attemptMaker.setCloudbreakClient(cloudbreakClient);
            return this;
        }

        public ClusterDeletionPollingAttemptMakerBuilder requestId(String requestId) {
            attemptMaker.setRequestId(requestId);
            return this;
        }

        public ClusterDeletionPollingAttemptMakerBuilder sdxCluster(SdxCluster sdxCluster) {
            attemptMaker.setSdxCluster(sdxCluster);
            return this;
        }

        public ClusterDeletionPollingAttemptMaker build() {
            return attemptMaker;
        }
    }

    @Override
    public AttemptResult process() throws Exception {
        LOGGER.info("Deletion polling cloudbreak for stack status: '{}' in '{}' env", sdxCluster.getClusterName(), sdxCluster.getEnvName());
        try {
            MDCBuilder.addRequestIdToMdcContext(requestId);
            StackV4Response stackV4Response = cloudbreakClient.withCrn(sdxCluster.getInitiatorUserCrn())
                    .stackV4Endpoint()
                    .get(0L, sdxCluster.getClusterName(), Collections.emptySet());
            LOGGER.info("Stack status of SDX {} by response from cloudbreak: {}", sdxCluster.getClusterName(),
                    stackV4Response.getStatus().name());
            LOGGER.debug("Response from cloudbreak: {}", JsonUtil.writeValueAsString(stackV4Response));
            if (Status.DELETE_FAILED.equals(stackV4Response.getStatus())) {
                notificationService.send(ResourceEvent.SDX_CLUSTER_DELETION_FAILED, sdxCluster);
                return AttemptResults.breakFor(
                        "Stack deletion failed '" + sdxCluster.getClusterName() + "', " + stackV4Response.getStatusReason()
                );
            } else {
                return AttemptResults.justContinue();
            }
        } catch (NotFoundException e) {
            notificationService.send(ResourceEvent.SDX_CLUSTER_DELETION_FINISHED, sdxCluster);
            return AttemptResults.finishWith(null);
        }
    }

    public void setNotificationService(SdxNotificationService notificationService) {
        this.notificationService = notificationService;
    }

    public void setCloudbreakClient(CloudbreakServiceUserCrnClient cloudbreakClient) {
        this.cloudbreakClient = cloudbreakClient;
    }

    public void setSdxCluster(SdxCluster sdxCluster) {
        this.sdxCluster = sdxCluster;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }
}
