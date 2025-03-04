package com.sequenceiq.redbeams.service.dbserverconfig;

import static com.sequenceiq.redbeams.service.RedbeamsConstants.DATABASE_TEST_RESULT_SUCCESS;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.validation.FieldError;
import org.springframework.validation.MapBindingResult;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Throwables;
import com.google.common.collect.Sets;
import com.sequenceiq.cloudbreak.auth.altus.Crn;
import com.sequenceiq.cloudbreak.common.archive.AbstractArchivistService;
import com.sequenceiq.cloudbreak.common.database.DatabaseCommon;
import com.sequenceiq.cloudbreak.common.service.Clock;
import com.sequenceiq.cloudbreak.logger.MDCBuilder;
import com.sequenceiq.redbeams.api.endpoint.v4.ResourceStatus;
import com.sequenceiq.redbeams.domain.DatabaseConfig;
import com.sequenceiq.redbeams.domain.DatabaseServerConfig;
import com.sequenceiq.redbeams.exception.BadRequestException;
import com.sequenceiq.redbeams.exception.NotFoundException;
import com.sequenceiq.redbeams.exception.RedbeamsException;
import com.sequenceiq.redbeams.repository.DatabaseServerConfigRepository;
import com.sequenceiq.redbeams.service.UserGeneratorService;
import com.sequenceiq.redbeams.service.crn.CrnService;
import com.sequenceiq.redbeams.service.dbconfig.DatabaseConfigService;
import com.sequenceiq.redbeams.service.drivers.DriverFunctions;
import com.sequenceiq.redbeams.service.validation.DatabaseServerConnectionValidator;

@Service
public class DatabaseServerConfigService extends AbstractArchivistService<DatabaseServerConfig> {

    private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseServerConfigService.class);

    private static final Pattern VALID_DATABASE_NAME = Pattern.compile("^[\\p{Alnum}_][\\p{Alnum}_-]*$");

    private static final Long DEFAULT_WORKSPACE = 0L;

    @Inject
    private DatabaseServerConfigRepository repository;

    @Inject
    private DatabaseConfigService databaseConfigService;

    @Inject
    private DriverFunctions driverFunctions;

    @Inject
    private DatabaseCommon databaseCommon;

    @Inject
    private DatabaseServerConnectionValidator connectionValidator;

    @Inject
    private Clock clock;

    @Inject
    private CrnService crnService;

    @Inject
    private UserGeneratorService userGeneratorService;

    public Set<DatabaseServerConfig> findAll(Long workspaceId, String environmentCrn) {
        if (environmentCrn == null) {
            throw new IllegalArgumentException("No environment CRN supplied.");
        }

        return repository.findByWorkspaceIdAndEnvironmentId(workspaceId, environmentCrn);
    }

    public DatabaseServerConfig create(DatabaseServerConfig resource, Long workspaceId, boolean test) {

        if (resource.getConnectionDriver() == null) {
            resource.setConnectionDriver(resource.getDatabaseVendor().connectionDriver());
            LOGGER.info("Database server configuration lacked a connection driver; defaulting to {}",
                    resource.getConnectionDriver());
        }

        if (test) {
            // FIXME? Currently no checks if logged-in user has access to workspace
            // Compare with AbstractWorkspaceAwareResourceService
            String testResults = testConnection(resource);

            if (!testResults.equals(DATABASE_TEST_RESULT_SUCCESS)) {
                throw new IllegalArgumentException(testResults);
            }
        }

        try {
            MDCBuilder.buildMdcContext(resource);
            // prepareCreation(resource);
            resource.setCreationDate(clock.getCurrentTimeMillis());
            if (resource.getResourceCrn() == null) {
                Crn crn = crnService.createCrn(resource);
                resource.setResourceCrn(crn);
                resource.setAccountId(crn.getAccountId());
            }
            resource.setWorkspaceId(workspaceId);
            return repository.save(resource);
        } catch (AccessDeniedException | DataIntegrityViolationException e) {
            Optional<Throwable> cve = Throwables.getCausalChain(e).stream()
                    .filter(c -> c instanceof ConstraintViolationException)
                    .findFirst();
            if (cve.isPresent()) {
                String message = String.format("%s already exists with name '%s' in workspace %d",
                        DatabaseServerConfig.class.getSimpleName(), resource.getName(), resource.getWorkspaceId());
                throw new BadRequestException(message, cve.get());
            }
            throw e;
        }
    }

    public DatabaseServerConfig update(DatabaseServerConfig resource) {
        MDCBuilder.buildMdcContext(resource);
        return repository.save(resource);
    }

    public void archive(DatabaseServerConfig resource) {
        for (DatabaseConfig dbConfig : resource.getDatabases()) {
            databaseConfigService.archive(dbConfig);
        }
        resource.setArchived(true);
        resource.setDbStack(null);
        repository.save(resource);
    }

    public DatabaseServerConfig getByName(Long workspaceId, String environmentCrn, String name) {
        Optional<DatabaseServerConfig> resourceOpt = repository.findByNameAndWorkspaceIdAndEnvironmentId(name, workspaceId, environmentCrn);
        if (resourceOpt.isEmpty()) {
            throw new NotFoundException(String.format("No %s found with name '%s' in environment '%s'",
                    DatabaseServerConfig.class.getSimpleName(), name, environmentCrn));
        }
        MDCBuilder.buildMdcContext(resourceOpt.get());
        return resourceOpt.get();
    }

    public DatabaseServerConfig getByCrn(String resourceCrn) {
        Crn crn = Crn.safeFromString(resourceCrn);
        Optional<DatabaseServerConfig> resourceOpt = repository.findByResourceCrn(crn);
        if (resourceOpt.isEmpty()) {
            throw new NotFoundException(String.format("No %s found with crn '%s'", DatabaseServerConfig.class.getSimpleName(), resourceCrn));
        }
        MDCBuilder.buildMdcContext(resourceOpt.get());
        return resourceOpt.get();
    }

    public Optional<DatabaseServerConfig> getByCrn(Crn resourceCrn) {
        return repository.findByResourceCrn(resourceCrn);
    }

    public DatabaseServerConfig deleteByName(String environmentCrn, String name) {
        DatabaseServerConfig resource = getByName(DEFAULT_WORKSPACE, environmentCrn, name);
        checkResourceCanBeDeleted(resource);
        return delete(resource);
    }

    public DatabaseServerConfig deleteByCrn(String crn) {
        DatabaseServerConfig resource = getByCrn(crn);
        checkResourceCanBeDeleted(resource);
        return delete(resource);
    }

    private void checkResourceCanBeDeleted(DatabaseServerConfig resource) {
        if (resource.getResourceStatus().equals(ResourceStatus.SERVICE_MANAGED)) {
            throw new BadRequestException("Cannot delete service managed configuration. "
                    + "Please use termination to stop the database-server and delete the configuration.");
        }
    }

    @Override
    public JpaRepository repository() {
        return repository;
    }

    public Set<DatabaseServerConfig> deleteMultipleByCrn(Set<String> crns) {
        Set<DatabaseServerConfig> resources = getByCrns(crns);
        return resources.stream()
                .map(this::delete)
                .collect(Collectors.toSet());
    }

    //    public Set<DatabaseServerConfig> deleteMultipleByName(Long workspaceId, String environmentCrn, Set<String> names) {
//        Set<DatabaseServerConfig> resources = getByNames(workspaceId, environmentCrn, names);
//        return resources.stream()
//                .map(this::delete)
//                .collect(Collectors.toSet());
//    }

    private Set<DatabaseServerConfig> getByNames(Long workspaceId, String environmentCrn, Set<String> names) {
        Set<DatabaseServerConfig> resources =
                repository.findByNameInAndWorkspaceIdAndEnvironmentId(names, workspaceId, environmentCrn);
        Set<String> notFound = Sets.difference(names,
                resources.stream().map(DatabaseServerConfig::getName).collect(Collectors.toSet()));

        if (!notFound.isEmpty()) {
            throw new NotFoundException(String.format("No %s(s) found with name(s) %s in environment '%s'", DatabaseServerConfig.class.getSimpleName(),
                    String.join(", ", notFound), environmentCrn));
        }

        return resources;
    }

    private Set<DatabaseServerConfig> getByCrns(Set<String> crns) {
        Set<Crn> parsedCrns = crns.stream()
                .map(Crn::safeFromString)
                .collect(Collectors.toSet());
        Set<DatabaseServerConfig> resources = repository.findByResourceCrnIn(parsedCrns);
        Set<String> notFound = Sets.difference(crns,
                resources.stream().map(dsc -> dsc.getResourceCrn().toString()).collect(Collectors.toSet()));

        if (!notFound.isEmpty()) {
            throw new NotFoundException(String.format("No %s(s) found with crn(s) %s ",
                    DatabaseServerConfig.class.getSimpleName(), String.join(", ", notFound)));
        }

        return resources;
    }

    public String testConnection(String crn) {
        return testConnection(getByCrn(crn));
    }

    public String testConnection(DatabaseServerConfig resource) {
        MapBindingResult errors = new MapBindingResult(new HashMap(), "databaseServer");
        connectionValidator.validate(resource, errors);
        if (!errors.hasErrors()) {
            return DATABASE_TEST_RESULT_SUCCESS;
        }
        return errors.getAllErrors().stream()
                .map(e -> (e instanceof FieldError ? ((FieldError) e).getField() + ": " : "") + e.getDefaultMessage())
                .collect(Collectors.joining("; "));
    }

    public String createDatabaseOnServer(String serverCrn, String databaseName, String databaseType) {
        // Prepared statements cannot be used for DDL statements, so we have to scrub the databaseName ourselves.
        // This is a subset of valid SQL identifiers, but I believe it's a sane constraint to put on database name
        // identifiers that protects us from SQL injections
        if (!validateDatabaseName(databaseName)) {
            throw new IllegalArgumentException("The database must contain only alphanumeric characters or underscores");
        }

        LOGGER.info("Creating database with name: {}", databaseName);

        DatabaseServerConfig databaseServerConfig = getByCrn(serverCrn);

        String databaseUserName = userGeneratorService.generateUserName();
        String databasePassword = userGeneratorService.generatePassword();
        List<String> sqlStrings = List.of(
                "CREATE DATABASE " + databaseName,
                "CREATE USER " + databaseUserName + " WITH ENCRYPTED PASSWORD '" + databasePassword + "'",
                "GRANT ALL PRIVILEGES ON DATABASE " + databaseName + " TO " + databaseUserName
        );

        // For now, do not use a transaction (PostgreSQL forbids it).
        boolean createDatabaseInsideTransaction = false;

        driverFunctions.execWithDatabaseDriver(databaseServerConfig, driver -> {
            try (Connection conn = driver.connect(databaseServerConfig)) {
                databaseCommon.executeUpdates(conn, sqlStrings, createDatabaseInsideTransaction);
            } catch (SQLException e) {
                throw new RedbeamsException("Failed to create database " + databaseName, e);
            }
        });

        // Only record database on server if successfully created on server
        DatabaseConfig newDatabaseConfig =
                databaseServerConfig.createDatabaseConfig(databaseName, databaseType, ResourceStatus.SERVICE_MANAGED,
                        databaseUserName, databasePassword);
        databaseConfigService.register(newDatabaseConfig, false);

        return "created";
    }

    @VisibleForTesting
    boolean validateDatabaseName(String databaseName) {
        return VALID_DATABASE_NAME.matcher(databaseName).matches();
    }
}
