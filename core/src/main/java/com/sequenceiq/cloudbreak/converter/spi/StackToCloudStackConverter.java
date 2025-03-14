package com.sequenceiq.cloudbreak.converter.spi;

import static com.sequenceiq.cloudbreak.api.endpoint.v4.stacks.base.InstanceStatus.REQUESTED;
import static com.sequenceiq.cloudbreak.cloud.model.InstanceStatus.CREATE_REQUESTED;
import static com.sequenceiq.cloudbreak.cloud.model.InstanceStatus.DELETE_REQUESTED;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.google.common.collect.Maps;
import com.sequenceiq.cloudbreak.api.util.ConverterUtil;
import com.sequenceiq.cloudbreak.cloud.model.CloudInstance;
import com.sequenceiq.cloudbreak.cloud.model.CloudStack;
import com.sequenceiq.cloudbreak.cloud.model.Group;
import com.sequenceiq.cloudbreak.cloud.model.Image;
import com.sequenceiq.cloudbreak.cloud.model.InstanceAuthentication;
import com.sequenceiq.cloudbreak.cloud.model.InstanceStatus;
import com.sequenceiq.cloudbreak.cloud.model.InstanceTemplate;
import com.sequenceiq.cloudbreak.cloud.model.Network;
import com.sequenceiq.cloudbreak.cloud.model.PortDefinition;
import com.sequenceiq.cloudbreak.cloud.model.Security;
import com.sequenceiq.cloudbreak.cloud.model.SecurityRule;
import com.sequenceiq.cloudbreak.cloud.model.SpiFileSystem;
import com.sequenceiq.cloudbreak.cloud.model.StackTags;
import com.sequenceiq.cloudbreak.cloud.model.StackTemplate;
import com.sequenceiq.cloudbreak.cloud.model.Subnet;
import com.sequenceiq.cloudbreak.cloud.model.Volume;
import com.sequenceiq.cloudbreak.cloud.model.filesystem.CloudFileSystemView;
import com.sequenceiq.cloudbreak.cmtemplate.CmTemplateProcessor;
import com.sequenceiq.cloudbreak.cmtemplate.CmTemplateProcessorFactory;
import com.sequenceiq.cloudbreak.common.json.Json;
import com.sequenceiq.cloudbreak.converter.InstanceMetadataToImageIdConverter;
import com.sequenceiq.cloudbreak.core.CloudbreakImageNotFoundException;
import com.sequenceiq.cloudbreak.domain.FileSystem;
import com.sequenceiq.cloudbreak.domain.StackAuthentication;
import com.sequenceiq.cloudbreak.domain.Template;
import com.sequenceiq.cloudbreak.domain.stack.Stack;
import com.sequenceiq.cloudbreak.domain.stack.cluster.Cluster;
import com.sequenceiq.cloudbreak.domain.stack.instance.InstanceGroup;
import com.sequenceiq.cloudbreak.domain.stack.instance.InstanceMetaData;
import com.sequenceiq.cloudbreak.service.ComponentConfigProviderService;
import com.sequenceiq.cloudbreak.service.image.ImageService;
import com.sequenceiq.cloudbreak.service.securityrule.SecurityRuleService;
import com.sequenceiq.cloudbreak.service.stack.DefaultRootVolumeSizeProvider;
import com.sequenceiq.cloudbreak.template.VolumeUtils;

@Component
public class StackToCloudStackConverter {

    private static final Logger LOGGER = LoggerFactory.getLogger(StackToCloudStackConverter.class);

    @Inject
    private SecurityRuleService securityRuleService;

    @Inject
    private ImageService imageService;

    @Inject
    private ComponentConfigProviderService componentConfigProviderService;

    @Inject
    private DefaultRootVolumeSizeProvider defaultRootVolumeSizeProvider;

    @Inject
    private InstanceMetadataToImageIdConverter instanceMetadataToImageIdConverter;

    @Inject
    private FileSystemConverter fileSystemConverter;

    @Inject
    private CmTemplateProcessorFactory cmTemplateProcessorFactory;

    @Inject
    private ConverterUtil converterUtil;

    @Inject
    private CloudFileSystemViewBuilder cloudFileSystemViewBuilder;

    public CloudStack convert(Stack stack) {
        return convert(stack, Collections.emptySet());
    }

    public CloudStack convertForDownscale(Stack stack, Set<String> deleteRequestedInstances) {
        return convert(stack, deleteRequestedInstances);
    }

    public CloudStack convertForTermination(Stack stack, String instanceId) {
        return convert(stack, Collections.singleton(instanceId));
    }

    public CloudStack convert(Stack stack, Collection<String> deleteRequestedInstances) {
        Image image = null;
        List<Group> instanceGroups = buildInstanceGroups(stack, stack.getInstanceGroupsAsList(), stack.getStackAuthentication(), deleteRequestedInstances);
        try {
            image = imageService.getImage(stack.getId());
        } catch (CloudbreakImageNotFoundException e) {
            LOGGER.debug(e.getMessage());
        }
        Network network = buildNetwork(stack);
        StackTemplate stackTemplate = componentConfigProviderService.getStackTemplate(stack.getId());
        InstanceAuthentication instanceAuthentication = buildInstanceAuthentication(stack.getStackAuthentication());
        SpiFileSystem cloudFileSystem = buildSpiFileSystem(stack);
        String template = null;
        if (stackTemplate != null) {
            template = stackTemplate.getTemplate();
        }

        return new CloudStack(instanceGroups, network, image, stack.getParameters(), getUserDefinedTags(stack), template,
                instanceAuthentication, instanceAuthentication.getLoginUserName(), instanceAuthentication.getPublicKey(), cloudFileSystem);
    }

    public List<CloudInstance> buildInstances(Stack stack) {
        List<Group> groups = buildInstanceGroups(stack, stack.getInstanceGroupsAsList(), stack.getStackAuthentication(), Collections.emptySet());
        List<CloudInstance> cloudInstances = new ArrayList<>();
        for (Group group : groups) {
            cloudInstances.addAll(group.getInstances());
        }
        return cloudInstances;
    }

    public CloudInstance buildInstance(InstanceMetaData instanceMetaData, Template template,
            StackAuthentication stackAuthentication, String name, Long privateId, InstanceStatus status) {
        String id = instanceMetaData == null ? null : instanceMetaData.getInstanceId();
        String hostName = instanceMetaData == null ? null : instanceMetaData.getShortHostname();
        String subnetId = instanceMetaData == null ? null : instanceMetaData.getSubnetId();
        String instanceName = instanceMetaData == null ? null : instanceMetaData.getInstanceName();
        String instanceImageId = instanceMetaData == null ? null : instanceMetadataToImageIdConverter.convert(instanceMetaData);

        InstanceTemplate instanceTemplate = buildInstanceTemplate(template, name, privateId, status, instanceImageId);
        InstanceAuthentication instanceAuthentication = buildInstanceAuthentication(stackAuthentication);
        Map<String, Object> params = new HashMap<>();
        if (hostName != null) {
            params.put(CloudInstance.DISCOVERY_NAME, hostName);
        }
        if (subnetId != null) {
            params.put(CloudInstance.SUBNET_ID, subnetId);
        }
        if (instanceName != null) {
            params.put(CloudInstance.INSTANCE_NAME, instanceName);
        }
        return new CloudInstance(id, instanceTemplate, instanceAuthentication, params);
    }

    InstanceTemplate buildInstanceTemplate(Template template, String name, Long privateId, InstanceStatus status, String instanceImageId) {
        Json attributesJson = template.getAttributes();
        Map<String, Object> attributes = Optional.ofNullable(attributesJson).map(Json::getMap).orElseGet(HashMap::new);
        Json fromVault = template.getSecretAttributes() == null ? null : new Json(template.getSecretAttributes());
        Map<String, Object> secretAttributes = Optional.ofNullable(fromVault).map(Json::getMap).orElseGet(HashMap::new);

        Map<String, Object> fields = new HashMap<>();
        fields.putAll(attributes);
        fields.putAll(secretAttributes);

        List<Volume> volumes = new ArrayList<>();
        template.getVolumeTemplates().stream().forEach(volumeModel -> {
            for (int i = 0; i < volumeModel.getVolumeCount(); i++) {
                Volume volume = new Volume(VolumeUtils.VOLUME_PREFIX + (i + 1), volumeModel.getVolumeType(), volumeModel.getVolumeSize());
                volumes.add(volume);
            }
        });
        return new InstanceTemplate(template.getInstanceType(), name, privateId, volumes, status, fields, template.getId(), instanceImageId);
    }

    private Map<String, String> getUserDefinedTags(Stack stack) {
        Map<String, String> result = Maps.newHashMap();
        try {
            if (stack.getTags() != null) {
                StackTags stackTag = stack.getTags().get(StackTags.class);
                Map<String, String> userDefined = stackTag.getUserDefinedTags();
                Map<String, String> defaultTags = stackTag.getDefaultTags();
                if (userDefined != null) {
                    result.putAll(userDefined);
                }
                if (defaultTags != null) {
                    result.putAll(defaultTags);
                }
            }
        } catch (IOException e) {
            LOGGER.info("Exception during converting user defined tags.", e);
        }
        return result;
    }

    private List<Group> buildInstanceGroups(Stack stack, List<InstanceGroup> instanceGroups,
            StackAuthentication stackAuthentication, Collection<String> deleteRequests) {
        // sort by name to avoid shuffling the different instance groups
        Collections.sort(instanceGroups);
        List<Group> groups = new ArrayList<>();
        Cluster cluster = stack.getCluster();
        if (cluster != null && cluster.getBlueprint() != null) {
            CmTemplateProcessor cmTemplateProcessor = cmTemplateProcessorFactory.get(cluster.getBlueprint().getBlueprintText());
            Map<String, Set<String>> componentsByHostGroup = cmTemplateProcessor.getComponentsByHostGroup();
            for (InstanceGroup instanceGroup : instanceGroups) {
                if (instanceGroup.getTemplate() != null) {
                    Template template = instanceGroup.getTemplate();
                    InstanceAuthentication instanceAuthentication = buildInstanceAuthentication(stackAuthentication);
                    Optional<CloudFileSystemView> cloudFileSystemView
                            = cloudFileSystemViewBuilder.build(cluster.getFileSystem(), componentsByHostGroup, instanceGroup);
                    groups.add(
                            new Group(instanceGroup.getGroupName(),
                                    instanceGroup.getInstanceGroupType(),
                                    buildCloudInstances(stackAuthentication, deleteRequests, instanceGroup, template),
                                    buildSecurity(instanceGroup),
                                    buildCloudInstanceSkeleton(stackAuthentication, instanceGroup, template),
                                    getFields(instanceGroup),
                                    instanceAuthentication,
                                    instanceAuthentication.getLoginUserName(),
                                    instanceAuthentication.getPublicKey(),
                                    getRootVolumeSize(instanceGroup),
                                    cloudFileSystemView)
                    );
                }
            }
        } else {
            LOGGER.warn("Cluster or blueprint is null for stack id:[{}] name:[{}]", stack.getId(), stack.getName());
        }
        return groups;
    }

    private InstanceAuthentication buildInstanceAuthentication(StackAuthentication stackAuthentication) {
        return new InstanceAuthentication(
                stackAuthentication.getPublicKey(),
                stackAuthentication.getPublicKeyId(),
                stackAuthentication.getLoginUserName());
    }

    private List<CloudInstance> buildCloudInstances(StackAuthentication stackAuthentication, Collection<String> deleteRequests,
            InstanceGroup instanceGroup, Template template) {
        List<CloudInstance> instances = new ArrayList<>();
        // existing instances
        for (InstanceMetaData metaData : instanceGroup.getNotDeletedInstanceMetaDataSet()) {
            InstanceStatus status = getInstanceStatus(metaData, deleteRequests);
            instances.add(buildInstance(metaData, template, stackAuthentication, instanceGroup.getGroupName(), metaData.getPrivateId(), status));
        }
        return instances;
    }

    private Security buildSecurity(InstanceGroup ig) {
        List<SecurityRule> rules = new ArrayList<>();
        if (ig.getSecurityGroup() == null) {
            return new Security(rules, Collections.emptyList());
        }
        Long id = ig.getSecurityGroup().getId();
        List<com.sequenceiq.cloudbreak.domain.SecurityRule> securityRules = securityRuleService.findAllBySecurityGroupId(id);
        for (com.sequenceiq.cloudbreak.domain.SecurityRule securityRule : securityRules) {
            List<PortDefinition> portDefinitions = new ArrayList<>();
            for (String actualPort : securityRule.getPorts()) {
                String[] segments = actualPort.split("-");
                if (segments.length > 1) {
                    portDefinitions.add(new PortDefinition(segments[0], segments[1]));
                } else {
                    portDefinitions.add(new PortDefinition(segments[0], segments[0]));
                }
            }

            rules.add(new SecurityRule(securityRule.getCidr(), portDefinitions.toArray(new PortDefinition[portDefinitions.size()]),
                    securityRule.getProtocol()));
        }
        return new Security(rules, ig.getSecurityGroup().getSecurityGroupIds(), true);
    }

    private CloudInstance buildCloudInstanceSkeleton(StackAuthentication stackAuthentication, InstanceGroup instanceGroup, Template template) {
        CloudInstance skeleton = null;
        if (instanceGroup.getNodeCount() == 0) {
            skeleton = buildInstance(null, template, stackAuthentication, instanceGroup.getGroupName(), 0L,
                    CREATE_REQUESTED);
        }
        return skeleton;
    }

    private Map<String, Object> getFields(InstanceGroup instanceGroup) {
        Json attributes = instanceGroup.getAttributes();
        return attributes == null ? Collections.emptyMap() : attributes.getMap();
    }

    private Integer getRootVolumeSize(InstanceGroup instanceGroup) {
        Integer rootVolumeSize = instanceGroup.getTemplate().getRootVolumeSize();
        if (Objects.isNull(rootVolumeSize)) {
            rootVolumeSize = defaultRootVolumeSizeProvider.getForPlatform(instanceGroup.getTemplate().cloudPlatform());
        }
        return rootVolumeSize;
    }

    private SpiFileSystem buildSpiFileSystem(Stack stack) {
        SpiFileSystem spiFileSystem = null;
        if (stack.getCluster() != null) {
            FileSystem fileSystem = stack.getCluster().getFileSystem();
            if (fileSystem != null) {
                spiFileSystem = fileSystemConverter.fileSystemToSpi(fileSystem);
            }
        }
        return spiFileSystem;
    }

    private Network buildNetwork(Stack stack) {
        com.sequenceiq.cloudbreak.domain.Network stackNetwork = stack.getNetwork();
        Network result = null;
        if (stackNetwork != null) {
            Subnet subnet = new Subnet(stackNetwork.getSubnetCIDR());
            Json attributes = stackNetwork.getAttributes();
            Map<String, Object> params = attributes == null ? Collections.emptyMap() : attributes.getMap();
            result = new Network(subnet, params);
        }
        return result;
    }

    private InstanceStatus getInstanceStatus(InstanceMetaData metaData, Collection<String> deleteRequests) {
        InstanceStatus status;
        if (deleteRequests.contains(metaData.getInstanceId())) {
            status = DELETE_REQUESTED;
        } else if (metaData.getInstanceStatus() == REQUESTED) {
            status = CREATE_REQUESTED;
        } else {
            status = InstanceStatus.CREATED;
        }
        return status;
    }

}
