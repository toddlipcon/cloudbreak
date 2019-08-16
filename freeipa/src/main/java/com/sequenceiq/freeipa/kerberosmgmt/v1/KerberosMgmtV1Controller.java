package com.sequenceiq.freeipa.kerberosmgmt.v1;

import javax.inject.Inject;
import javax.transaction.Transactional;
import javax.validation.Valid;

import org.springframework.stereotype.Controller;

import com.sequenceiq.freeipa.api.v1.kerberosmgmt.KerberosMgmtV1Endpoint;
import com.sequenceiq.freeipa.api.v1.kerberosmgmt.model.VaultCleanupRequest;
import com.sequenceiq.freeipa.api.v1.kerberosmgmt.model.HostRequest;
import com.sequenceiq.freeipa.api.v1.kerberosmgmt.model.ServiceKeytabRequest;
import com.sequenceiq.freeipa.api.v1.kerberosmgmt.model.ServiceKeytabResponse;
import com.sequenceiq.freeipa.api.v1.kerberosmgmt.model.ServicePrincipalRequest;
import com.sequenceiq.freeipa.client.FreeIpaClientException;
import com.sequenceiq.freeipa.kerberosmgmt.exception.DeleteException;
import com.sequenceiq.freeipa.util.CrnService;

@Controller
@Transactional(Transactional.TxType.NEVER)
public class KerberosMgmtV1Controller implements KerberosMgmtV1Endpoint {
    @Inject
    private KerberosMgmtV1Service kerberosMgmtV1Service;

    @Inject
    private CrnService crnService;

    public ServiceKeytabResponse generateServiceKeytab(@Valid ServiceKeytabRequest request) throws FreeIpaClientException {
        String accountId = crnService.getCurrentAccountId();
        return kerberosMgmtV1Service.generateServiceKeytab(request, accountId);
    }

    public ServiceKeytabResponse getServiceKeytab(@Valid ServiceKeytabRequest request) throws FreeIpaClientException {
        String accountId = crnService.getCurrentAccountId();
        return kerberosMgmtV1Service.getExistingServiceKeytab(request, accountId);
    }

    public void deleteServicePrincipalEntityBody(@Valid ServicePrincipalRequest request) throws FreeIpaClientException, DeleteException {
        deleteServicePrincipal(request.getEnvironmentCrn(), request.getServerHostName(), request.getServiceName(), request.getClusterCrn());
    }

    public void deleteServicePrincipal(String environmentCrn, String serverHostName, String serviceName, String clusterCrn)
            throws FreeIpaClientException, DeleteException {
        String accountId = crnService.getCurrentAccountId();
        kerberosMgmtV1Service.deleteServicePrincipal(environmentCrn, serverHostName, serviceName, clusterCrn, accountId);
    }

    public void deleteHostEntityBody(@Valid HostRequest request) throws FreeIpaClientException, DeleteException {
        deleteHost(request.getEnvironmentCrn(), request.getServerHostName(), request.getClusterCrn());
    }

    public void deleteHost(String environmentCrn, String serverHostName, String clusterCrn) throws FreeIpaClientException, DeleteException {
        String accountId = crnService.getCurrentAccountId();
        kerberosMgmtV1Service.deleteHost(environmentCrn, serverHostName, clusterCrn, accountId);
    }

    public void cleanupClusterSecretsEntityBody(@Valid VaultCleanupRequest request) throws DeleteException {
        cleanupClusterSecrets(request.getEnvironmentCrn(), request.getClusterCrn());
    }

    public void cleanupClusterSecrets(String environmentCrn, String clusterCrn) throws DeleteException {
        String accountId = crnService.getCurrentAccountId();
        kerberosMgmtV1Service.cleanupByCluster(environmentCrn, clusterCrn, accountId);
    }
}
