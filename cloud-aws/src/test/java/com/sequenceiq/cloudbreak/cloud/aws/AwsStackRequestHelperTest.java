package com.sequenceiq.cloudbreak.cloud.aws;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import com.amazonaws.services.cloudformation.model.CreateStackRequest;
import com.amazonaws.services.cloudformation.model.Parameter;
import com.amazonaws.services.cloudformation.model.Tag;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.DescribeImagesRequest;
import com.amazonaws.services.ec2.model.DescribeImagesResult;
import com.google.common.collect.Lists;
import com.sequenceiq.cloudbreak.cloud.aws.view.AwsCredentialView;
import com.sequenceiq.cloudbreak.cloud.context.AuthenticatedContext;
import com.sequenceiq.cloudbreak.cloud.context.CloudContext;
import com.sequenceiq.cloudbreak.cloud.model.AvailabilityZone;
import com.sequenceiq.cloudbreak.cloud.model.CloudStack;
import com.sequenceiq.cloudbreak.cloud.model.DatabaseEngine;
import com.sequenceiq.cloudbreak.cloud.model.DatabaseServer;
import com.sequenceiq.cloudbreak.cloud.model.DatabaseStack;
import com.sequenceiq.cloudbreak.cloud.model.Image;
import com.sequenceiq.cloudbreak.cloud.model.Location;
import com.sequenceiq.cloudbreak.cloud.model.Network;
import com.sequenceiq.cloudbreak.cloud.model.Region;
import com.sequenceiq.cloudbreak.cloud.model.Security;

public class AwsStackRequestHelperTest {

    @Mock
    private AwsTagPreparationService awsTagPreparationService;

    @Mock
    private AwsClient awsClient;

    @Mock
    private AuthenticatedContext authenticatedContext;

    @Mock
    private CloudContext cloudContext;

    @Mock
    private CloudStack cloudStack;

    @Mock
    private DatabaseStack databaseStack;

    @Mock
    private Image image;

    @Mock
    private Network network;

    @Mock(answer = RETURNS_DEEP_STUBS)
    private DatabaseServer databaseServer;

    @Mock
    private Security security;

    @Mock
    private AmazonEC2Client amazonEC2Client;

    @InjectMocks
    private AwsStackRequestHelper underTest;

    @Before
    public void setUp() {
        initMocks(this);

        when(authenticatedContext.getCloudContext()).thenReturn(cloudContext);

        when(cloudStack.getImage()).thenReturn(image);
        when(cloudStack.getNetwork()).thenReturn(network);
        when(databaseStack.getNetwork()).thenReturn(network);
        when(databaseStack.getDatabaseServer()).thenReturn(databaseServer);

        when(databaseServer.getSecurity()).thenReturn(security);

        when(awsClient.createAccess(any(AwsCredentialView.class), anyString())).thenReturn(amazonEC2Client);
    }

    @Test
    public void testCreateCreateStackRequestForCloudStack() {
        when(cloudContext.getLocation()).thenReturn(Location.location(Region.region("region"), new AvailabilityZone("az")));
        DescribeImagesResult imagesResult = new DescribeImagesResult();
        when(amazonEC2Client.describeImages(any(DescribeImagesRequest.class)))
                .thenReturn(imagesResult.withImages(new com.amazonaws.services.ec2.model.Image()));
        when(network.getStringParameter(anyString())).thenReturn("");

        Collection<Tag> tags = Lists.newArrayList(new Tag().withKey("mytag").withValue("myvalue"));
        when(awsTagPreparationService.prepareCloudformationTags(authenticatedContext, cloudStack.getTags())).thenReturn(tags);

        CreateStackRequest createStackRequest =
                underTest.createCreateStackRequest(authenticatedContext, cloudStack, "stackName", "subnet", "template");

        assertEquals("stackName", createStackRequest.getStackName());
        assertEquals("template", createStackRequest.getTemplateBody());

        verify(awsTagPreparationService).prepareCloudformationTags(authenticatedContext, cloudStack.getTags());
        assertEquals(tags, createStackRequest.getTags());
    }

    @Test
    public void testCreateCreateStackRequestForDatabaseStack() {
        Collection<Tag> tags = Lists.newArrayList(new Tag().withKey("mytag").withValue("myvalue"));
        when(awsTagPreparationService.prepareCloudformationTags(authenticatedContext, databaseStack.getTags())).thenReturn(tags);

        CreateStackRequest createStackRequest =
                underTest.createCreateStackRequest(authenticatedContext, databaseStack, "stackName", "template");

        assertEquals("stackName", createStackRequest.getStackName());
        assertEquals("template", createStackRequest.getTemplateBody());

        verify(awsTagPreparationService).prepareCloudformationTags(authenticatedContext, cloudStack.getTags());
        assertEquals(tags, createStackRequest.getTags());
    }

    @Test
    public void testGetStackParameters() {
        when(network.getStringParameter("subnetId")).thenReturn("subnet-1234");

        when(databaseServer.getStorageSize()).thenReturn(Long.valueOf(50L));
        when(databaseServer.getParameter("backupRetentionPeriod", Integer.class)).thenReturn(Integer.valueOf(1));
        when(databaseServer.getFlavor()).thenReturn("db.m3.medium");
        when(databaseServer.getServerId()).thenReturn("myserver");
        when(databaseServer.getEngine()).thenReturn(DatabaseEngine.POSTGRESQL);
        when(databaseServer.getStringParameter("engineVersion")).thenReturn("10.6");
        when(databaseServer.getPort()).thenReturn(Integer.valueOf(5432));
        when(databaseServer.getRootUserName()).thenReturn("root");
        when(databaseServer.getRootPassword()).thenReturn("cloudera");
        when(databaseServer.getSecurity().getCloudSecurityIds()).thenReturn(List.of("sg-1234", "sg-5678"));

        when(cloudContext.getUserName()).thenReturn("bob@cloudera.com");

        Collection<Parameter> parameters = underTest.getStackParameters(authenticatedContext, databaseStack);

        assertContainsParameter(parameters, "AllocatedStorageParameter", "50");
        assertContainsParameter(parameters, "BackupRetentionPeriodParameter", "1");
        assertContainsParameter(parameters, "DBInstanceClassParameter", "db.m3.medium");
        assertContainsParameter(parameters, "DBInstanceIdentifierParameter", "myserver");
        assertContainsParameter(parameters, "DBSubnetGroupNameParameter", "dsg-myserver");
        assertContainsParameter(parameters, "DBSubnetGroupSubnetIdsParameter", "subnet-1234");
        assertContainsParameter(parameters, "EngineParameter", "postgres");
        assertContainsParameter(parameters, "EngineVersionParameter", "10.6");
        assertContainsParameter(parameters, "MasterUsernameParameter", "root");
        assertContainsParameter(parameters, "MasterUserPasswordParameter", "cloudera");
        assertContainsParameter(parameters, "PortParameter", "5432");
        assertContainsParameter(parameters, "VPCSecurityGroupsParameter", "sg-1234,sg-5678");
        assertContainsParameter(parameters, "StackOwner", "bob@cloudera.com");
    }

    @Test
    public void testGetMinimalStackParameters() {
        when(network.getStringParameter("subnetId")).thenReturn("subnet-1234");

        when(databaseServer.getStorageSize()).thenReturn(null);
        when(databaseServer.getParameter("backupRetentionPeriod", Integer.class)).thenReturn(null);
        when(databaseServer.getFlavor()).thenReturn("db.m3.medium");
        when(databaseServer.getServerId()).thenReturn("myserver");
        when(databaseServer.getEngine()).thenReturn(DatabaseEngine.POSTGRESQL);
        when(databaseServer.getStringParameter("engineVersion")).thenReturn(null);
        when(databaseServer.getPort()).thenReturn(null);
        when(databaseServer.getRootUserName()).thenReturn("root");
        when(databaseServer.getRootPassword()).thenReturn("cloudera");
        when(databaseServer.getSecurity().getCloudSecurityIds()).thenReturn(List.of("sg-1234", "sg-5678"));

        when(cloudContext.getUserName()).thenReturn("bob@cloudera.com");

        Collection<Parameter> parameters = underTest.getStackParameters(authenticatedContext, databaseStack);

        assertDoesNotContainParameter(parameters, "AllocatedStorageParameter");
        assertDoesNotContainParameter(parameters, "BackupRetentionPeriodParameter");
        assertDoesNotContainParameter(parameters, "EngineVersionParameter");
        assertDoesNotContainParameter(parameters, "PortParameter");
    }

    private void assertContainsParameter(Collection<Parameter> parameters, String key, String value) {
        Optional<Parameter> foundParameterOpt = parameters.stream()
            .filter(p -> p.getParameterKey().equals(key))
            .findFirst();
        assertTrue("Parameters are missing " + key, foundParameterOpt.isPresent());
        String foundValue = foundParameterOpt.get().getParameterValue();
        assertEquals("Parameter " + key + " should have value " + value + " but has value " + foundValue,
            value, foundValue);
    }

    private void assertDoesNotContainParameter(Collection<Parameter> parameters, String key) {
        Optional<Parameter> foundParameterOpt = parameters.stream()
            .filter(p -> p.getParameterKey().equals(key))
            .findFirst();
        assertFalse("Parameters include " + key, foundParameterOpt.isPresent());
    }
}
