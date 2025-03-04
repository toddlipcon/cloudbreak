package com.sequenceiq.cloudbreak.cm.polling.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cloudera.api.swagger.CommandsResourceApi;
import com.cloudera.api.swagger.client.ApiClient;
import com.cloudera.api.swagger.client.ApiException;
import com.cloudera.api.swagger.model.ApiCommand;
import com.sequenceiq.cloudbreak.cluster.service.ClusterBasedStatusCheckerTask;
import com.sequenceiq.cloudbreak.cm.ClouderaManagerOperationFailedException;
import com.sequenceiq.cloudbreak.cm.polling.ClouderaManagerCommandPollerObject;

public abstract class AbstractClouderaManagerCommandCheckerTask<T extends ClouderaManagerCommandPollerObject> extends ClusterBasedStatusCheckerTask<T> {

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractClouderaManagerCommandCheckerTask.class);

    private static final int BAD_GATEWAY = 502;

    @Override
    public boolean checkStatus(T pollerObject) {
        ApiClient apiClient = pollerObject.getApiClient();
        CommandsResourceApi commandsResourceApi = new CommandsResourceApi(apiClient);
        try {
            ApiCommand apiCommand = commandsResourceApi.readCommand(pollerObject.getId());

            if (apiCommand.getActive()) {
                LOGGER.debug("Command [" + getCommandName() + "] with id [" + pollerObject.getId() + "] is active, so it hasn't finished yet");
                return false;
            } else if (apiCommand.getSuccess()) {
                return true;
            } else {
                String resultMessage = apiCommand.getResultMessage();
                LOGGER.info("Command [" + getCommandName() + "] failed: " + resultMessage);
                throw new ClouderaManagerOperationFailedException("Command [" + getCommandName() + "] failed: " + resultMessage);
            }
        } catch (ApiException e) {
            if (e.getCode() == BAD_GATEWAY) {
                LOGGER.debug("Cloudera Manager is not running", e);
                return false;
            } else {
                throw new ClouderaManagerOperationFailedException("Cloudera Manager operation failed", e);
            }
        }
    }

    protected abstract String getCommandName();
}
