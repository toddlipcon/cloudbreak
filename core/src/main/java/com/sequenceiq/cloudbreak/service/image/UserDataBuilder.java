package com.sequenceiq.cloudbreak.service.image;

import java.io.IOException;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.io.BaseEncoding;
import com.sequenceiq.common.api.type.InstanceGroupType;
import com.sequenceiq.cloudbreak.cloud.PlatformParameters;
import com.sequenceiq.cloudbreak.cloud.exception.CloudConnectorException;
import com.sequenceiq.cloudbreak.cloud.model.Platform;
import com.sequenceiq.cloudbreak.util.FreeMarkerTemplateUtils;

import freemarker.template.Configuration;
import freemarker.template.TemplateException;

@Component
public class UserDataBuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger(UserDataBuilder.class);

    @Inject
    private UserDataBuilderParams userDataBuilderParams;

    @Inject
    private Configuration freemarkerConfiguration;

    @Inject
    private FreeMarkerTemplateUtils freeMarkerTemplateUtils;

    Map<InstanceGroupType, String> buildUserData(Platform cloudPlatform, byte[] cbSshKeyDer, String sshUser,
        PlatformParameters parameters, String saltBootPassword, String cbCert) {
        Map<InstanceGroupType, String> result = new EnumMap<>(InstanceGroupType.class);
        for (InstanceGroupType type : InstanceGroupType.values()) {
            String userData = build(type, cloudPlatform, cbSshKeyDer, sshUser, parameters, saltBootPassword, cbCert);
            result.put(type, userData);
            LOGGER.debug("User data for {}, content; {}", type, userData);
        }
        return result;
    }

    private String build(InstanceGroupType type, Platform cloudPlatform, byte[] cbSshKeyDer, String sshUser,
        PlatformParameters params, String saltBootPassword, String cbCert) {
        Map<String, Object> model = new HashMap<>();
        model.put("cloudPlatform", cloudPlatform.value());
        model.put("platformDiskPrefix", params.scriptParams().getDiskPrefix());
        model.put("platformDiskStartLabel", params.scriptParams().getStartLabel());
        model.put("gateway", type == InstanceGroupType.GATEWAY);
        model.put("tmpSshKey", "#NOT_USER_ANYMORE_BUT_KEEP_FOR_BACKWARD_COMPATIBILITY");
        model.put("signaturePublicKey", BaseEncoding.base64().encode(cbSshKeyDer));
        model.put("sshUser", sshUser);
        model.put("customUserData", userDataBuilderParams.getCustomData());
        model.put("saltBootPassword", saltBootPassword);
        model.put("cbCert", cbCert);
        return build(model);
    }

    private String build(Map<String, Object> model) {
        try {
            return freeMarkerTemplateUtils.processTemplateIntoString(freemarkerConfiguration.getTemplate("init/init.ftl", "UTF-8"), model);
        } catch (IOException | TemplateException e) {
            LOGGER.error(e.getMessage(), e);
            throw new CloudConnectorException("Failed to process init script freemarker template", e);
        }
    }

    @VisibleForTesting
    void setFreemarkerConfiguration(Configuration freemarkerConfiguration) {
        this.freemarkerConfiguration = freemarkerConfiguration;
    }
}
