package com.sequenceiq.it.cloudbreak.dto.freeipa;

import static com.sequenceiq.it.cloudbreak.context.RunningParameter.emptyRunningParameter;
import static com.sequenceiq.it.cloudbreak.context.RunningParameter.key;

import java.util.Collection;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.inject.Inject;

import com.sequenceiq.cloudbreak.api.endpoint.v4.stacks.request.authentication.StackAuthenticationV4Request;
import com.sequenceiq.cloudbreak.api.endpoint.v4.stacks.request.environment.placement.PlacementSettingsV4Request;
import com.sequenceiq.cloudbreak.api.endpoint.v4.stacks.request.instancegroup.InstanceGroupV4Request;
import com.sequenceiq.cloudbreak.api.endpoint.v4.stacks.request.network.NetworkV4Request;
import com.sequenceiq.freeipa.api.v1.freeipa.stack.model.FreeIpaServerRequest;
import com.sequenceiq.freeipa.api.v1.freeipa.stack.model.common.image.ImageSettingsRequest;
import com.sequenceiq.freeipa.api.v1.freeipa.stack.model.common.instance.InstanceGroupRequest;
import com.sequenceiq.freeipa.api.v1.freeipa.stack.model.common.instance.InstanceGroupType;
import com.sequenceiq.freeipa.api.v1.freeipa.stack.model.common.instance.InstanceTemplateRequest;
import com.sequenceiq.freeipa.api.v1.freeipa.stack.model.common.instance.VolumeRequest;
import com.sequenceiq.freeipa.api.v1.freeipa.stack.model.common.network.AwsNetworkParameters;
import com.sequenceiq.freeipa.api.v1.freeipa.stack.model.common.network.NetworkRequest;
import com.sequenceiq.freeipa.api.v1.freeipa.stack.model.common.region.PlacementRequest;
import com.sequenceiq.freeipa.api.v1.freeipa.stack.model.common.security.SecurityGroupRequest;
import com.sequenceiq.freeipa.api.v1.freeipa.stack.model.common.security.SecurityRuleRequest;
import com.sequenceiq.freeipa.api.v1.freeipa.stack.model.common.security.StackAuthenticationRequest;
import com.sequenceiq.freeipa.api.v1.freeipa.stack.model.create.CreateFreeIpaRequest;
import com.sequenceiq.freeipa.api.v1.freeipa.stack.model.describe.DescribeFreeIpaResponse;
import com.sequenceiq.freeipa.api.v1.freeipa.stack.model.list.ListFreeIpaResponse;
import com.sequenceiq.it.cloudbreak.CloudbreakClient;
import com.sequenceiq.it.cloudbreak.FreeIPAClient;
import com.sequenceiq.it.cloudbreak.Prototype;
import com.sequenceiq.it.cloudbreak.client.FreeIPATestClient;
import com.sequenceiq.it.cloudbreak.context.Purgable;
import com.sequenceiq.it.cloudbreak.context.RunningParameter;
import com.sequenceiq.it.cloudbreak.context.TestContext;
import com.sequenceiq.it.cloudbreak.dto.AbstractFreeIPATestDto;
import com.sequenceiq.it.cloudbreak.dto.CloudbreakTestDto;
import com.sequenceiq.it.cloudbreak.dto.InstanceGroupTestDto;
import com.sequenceiq.it.cloudbreak.dto.NetworkV4TestDto;
import com.sequenceiq.it.cloudbreak.dto.PlacementSettingsTestDto;
import com.sequenceiq.it.cloudbreak.dto.StackAuthenticationTestDto;
import com.sequenceiq.it.cloudbreak.dto.environment.EnvironmentTestDto;

@Prototype
public class FreeIPATestDto extends AbstractFreeIPATestDto<CreateFreeIpaRequest, DescribeFreeIpaResponse, FreeIPATestDto>
        implements Purgable<ListFreeIpaResponse, FreeIPAClient> {

    @Inject
    private FreeIPATestClient freeIPATestClient;

    public FreeIPATestDto(TestContext testContext) {
        super(new CreateFreeIpaRequest(), testContext);
    }

    @Override
    public FreeIPATestDto valid() {
        return withName(resourceProperyProvider().getName())
                .withEnvironment(getTestContext().given(EnvironmentTestDto.class))
                .withPlacement(getTestContext().given(PlacementSettingsTestDto.class))
                .withInstanceGroupsEntity(InstanceGroupTestDto.defaultHostGroup(getTestContext()))
                .withNetwork(getTestContext().given(NetworkV4TestDto.class))
                .withGatewayPort(getCloudProvider().gatewayPort(this))

                .withAuthentication(getCloudProvider().stackAuthentication(given(StackAuthenticationTestDto.class)))
                .withFreeIPA("ipatest.local", "ipaserver", "admin1234", "admins");
    }

    private FreeIPATestDto withFreeIPA(String domain, String hostname, String adminPassword, String adminGroupName) {
        FreeIpaServerRequest request = new FreeIpaServerRequest();
        request.setDomain(domain);
        request.setHostname(hostname);
        request.setAdminPassword(adminPassword);
        request.setAdminGroupName(adminGroupName);
        getRequest().setFreeIpa(request);
        return this;
    }

    private FreeIPATestDto withName(String name) {
        getRequest().setName(name);
        setName(name);
        return this;
    }

    private FreeIPATestDto withPlacement(PlacementSettingsTestDto placementSettings) {
        PlacementSettingsV4Request request = placementSettings.getRequest();
        getRequest().setPlacement(new PlacementRequest()
                .withAvailabilityZone(request.getAvailabilityZone())
                .withRegion(request.getRegion()));
        return this;
    }

    private FreeIPATestDto withInstanceGroupsEntity(Collection<InstanceGroupTestDto> instanceGroups) {
        getRequest().setInstanceGroups(instanceGroups.stream()
                .filter(instanceGroupTestDto -> "master".equals(instanceGroupTestDto.getRequest().getName()))
                .limit(1)
                .map(InstanceGroupTestDto::getRequest)
                .map(mapInstanceGroupRequest())
                .collect(Collectors.toList()));
        return this;
    }

    private Function<InstanceGroupV4Request, InstanceGroupRequest> mapInstanceGroupRequest() {
        return request -> {
            InstanceGroupRequest instanceGroupRequest = new InstanceGroupRequest();
            instanceGroupRequest.setNodeCount(request.getNodeCount());
            instanceGroupRequest.setName("master");
            instanceGroupRequest.setType(InstanceGroupType.MASTER);
            instanceGroupRequest.setInstanceTemplateRequest(mapInstanceTemplateRequest(request));
            instanceGroupRequest.setSecurityGroup(mapSecurityGroupRequest(request));
            return instanceGroupRequest;
        };
    }

    private SecurityGroupRequest mapSecurityGroupRequest(InstanceGroupV4Request request) {
        SecurityGroupRequest securityGroup = new SecurityGroupRequest();
        securityGroup.setSecurityRules(request.getSecurityGroup().getSecurityRules()
            .stream()
            .map(sgreq -> {
                SecurityRuleRequest rule = new SecurityRuleRequest();
                rule.setModifiable(sgreq.isModifiable());
                rule.setPorts(sgreq.getPorts());
                rule.setProtocol(sgreq.getProtocol());
                rule.setSubnet(sgreq.getSubnet());
                return rule;
            })
            .collect(Collectors.toList()));
        securityGroup.setSecurityGroupIds(request.getSecurityGroup().getSecurityGroupIds());
        return securityGroup;
    }

    private InstanceTemplateRequest mapInstanceTemplateRequest(InstanceGroupV4Request request) {
        InstanceTemplateRequest template = new InstanceTemplateRequest();
        template.setInstanceType(request.getTemplate().getInstanceType());
        template.setAttachedVolumes(request.getTemplate().getAttachedVolumes()
            .stream()
            .map(volreq -> {
                VolumeRequest volumeRequest = new VolumeRequest();
                volumeRequest.setCount(volreq.getCount());
                volumeRequest.setSize(volreq.getSize());
                volumeRequest.setType(volreq.getType());
                return volumeRequest;
            })
            .collect(Collectors.toSet()));
        return template;
    }

    private FreeIPATestDto withNetwork(NetworkV4TestDto network) {
        NetworkV4Request request = network.getRequest();
        NetworkRequest networkRequest = new NetworkRequest();
        if (request.getAws() != null) {
            AwsNetworkParameters params = new AwsNetworkParameters();
            params.setSubnetId(request.getAws().getSubnetId());
            params.setVpcId(request.getAws().getVpcId());
            networkRequest.setAws(params);
        }
        getRequest().setNetwork(networkRequest);
        return this;
    }

    private FreeIPATestDto withAuthentication(StackAuthenticationTestDto stackAuthentication) {
        StackAuthenticationV4Request request = stackAuthentication.getRequest();
        StackAuthenticationRequest authReq = new StackAuthenticationRequest();
        authReq.setLoginUserName(request.getLoginUserName());
        authReq.setPublicKey(request.getPublicKey());
        authReq.setPublicKeyId(request.getPublicKeyId());
        getRequest().setAuthentication(authReq);
        return this;
    }

    private FreeIPATestDto withEnvironment(EnvironmentTestDto environment) {
        getRequest().setEnvironmentCrn(environment.getResponse().getCrn());
        return this;
    }

    public FreeIPATestDto withCatalog(String catalog) {
        ImageSettingsRequest imageSettingsRequest = new ImageSettingsRequest();
        imageSettingsRequest.setCatalog(catalog);
        getRequest().setImage(imageSettingsRequest);
        return this;
    }

    public FreeIPATestDto await(com.sequenceiq.freeipa.api.v1.freeipa.stack.model.common.Status status) {
        return await(status, emptyRunningParameter());
    }

    public FreeIPATestDto await(com.sequenceiq.freeipa.api.v1.freeipa.stack.model.common.Status status, RunningParameter runningParameter) {
        return getTestContext().await(this, status, runningParameter);
    }

    public FreeIPATestDto withGatewayPort(Integer port) {
        getRequest().setGatewayPort(port);
        return this;
    }

    @Override
    public CloudbreakTestDto refresh(TestContext context, CloudbreakClient cloudbreakClient) {
        return when(freeIPATestClient.describe(), key("refresh-freeipa-" + getName()));
    }

    @Override
    public Collection<ListFreeIpaResponse> getAll(FreeIPAClient client) {
        return client.getFreeIpaClient().getFreeIpaV1Endpoint().list();
    }

    @Override
    public boolean deletable(ListFreeIpaResponse entity) {
        return entity.getName().startsWith(resourceProperyProvider().prefix());
    }

    @Override
    public void delete(TestContext testContext, ListFreeIpaResponse entity, FreeIPAClient client) {
        client.getFreeIpaClient().getFreeIpaV1Endpoint().delete(entity.getEnvironmentCrn());
    }

    @Override
    public Class<FreeIPAClient> client() {
        return FreeIPAClient.class;
    }
}
