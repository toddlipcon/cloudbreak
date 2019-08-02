package com.sequenceiq.it.cloudbreak.testcase.mock;

import javax.inject.Inject;

import org.testng.annotations.Test;

import com.sequenceiq.it.cloudbreak.client.BlueprintTestClient;
import com.sequenceiq.it.cloudbreak.client.DistroXTestClient;
import com.sequenceiq.it.cloudbreak.context.Description;
import com.sequenceiq.it.cloudbreak.context.TestContext;
import com.sequenceiq.it.cloudbreak.dto.blueprint.BlueprintTestDto;
import com.sequenceiq.it.cloudbreak.dto.distrox.DistroXTestDto;
import com.sequenceiq.it.cloudbreak.dto.distrox.cluster.DistroXClusterTestDto;
import com.sequenceiq.it.cloudbreak.dto.distrox.cluster.clouderamanager.DistroXClouderaManagerTestDto;
import com.sequenceiq.it.cloudbreak.dto.distrox.image.DistroXImageTestDto;
import com.sequenceiq.it.cloudbreak.dto.distrox.instancegroup.DistroXNetworkTestDto;
import com.sequenceiq.it.cloudbreak.dto.imagecatalog.ImageCatalogTestDto;

public class DistroXClusterCreationTest extends AbstractClouderaManagerTest {

    private static final String IMAGE_CATALOG_ID = "f6e778fc-7f17-4535-9021-515351df3691";

    /* TODO: 2019-08-08 Goals:
            - create cluster with bare minimum configs
            - create cluster with rds config(s) attached, and validate it afterward
            - create cluster with file system configs and validate them
            - validate cm template what we generated
     */

    @Inject
    private BlueprintTestClient blueprintTestClient;

    @Inject
    private DistroXTestClient distroXClient;

    /*@Override
    protected void setupTest(TestContext testContext) {
        createDefaultUser(testContext);
        createDefaultCredential(testContext);
        createDefaultEnvironmentWithNetwork(testContext);
        createDefaultImageCatalog(testContext);
        initializeDefaultBlueprints(testContext);
    }*/

    @Test(dataProvider = TEST_CONTEXT_WITH_MOCK)
    @Description(
            given = "there is a running cloudbreak",
            when = "a DistroX with Cloudera Manager is created",
            then = "the cluster should be available")
    public void testCreateNewRegularCluster(TestContext testContext) {
        String blueprintName = testContext.get(BlueprintTestDto.class).getRequest().getName();
        String cmKey = "cm";
        String clusterKey = "cmdistrox";
        String dixImgKey = "dixImg";
        String dixNetKey = "dixNet";
        testContext
                .given(dixNetKey, DistroXNetworkTestDto.class)
                .given(dixImgKey, DistroXImageTestDto.class)
                .withImageCatalog(getImageCatalogName(testContext))
                .withImageId(IMAGE_CATALOG_ID)
                .given(cmKey, DistroXClouderaManagerTestDto.class)
                .given(clusterKey, DistroXClusterTestDto.class)
                .withBlueprintName(blueprintName)
                .withValidateBlueprint(false)
                .withClouderaManager(cmKey)
                .given(DistroXTestDto.class)
                .withCluster(clusterKey)
                .withImageSettings(dixImgKey)
                .withNetwork(dixNetKey)
                .when(distroXClient.create())
                .await(STACK_AVAILABLE)
                .validate();
    }

    @Override
    protected BlueprintTestClient blueprintTestClient() {
        return blueprintTestClient;
    }

    private String getImageCatalogName(TestContext testContext) {
        return testContext.get(ImageCatalogTestDto.class).getRequest().getName();
    }

}
