/*
 * Copyright 2011 ingenieux Labs
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package br.com.ingenieux.jenkins.plugins.awsebdeployment.cmd;

import com.amazonaws.services.elasticbeanstalk.AWSElasticBeanstalkClient;
import com.amazonaws.services.elasticbeanstalk.model.AbortEnvironmentUpdateRequest;
import com.amazonaws.services.elasticbeanstalk.model.CreateApplicationVersionRequest;
import com.amazonaws.services.elasticbeanstalk.model.CreateApplicationVersionResult;
import com.amazonaws.services.elasticbeanstalk.model.DescribeEnvironmentsRequest;
import com.amazonaws.services.elasticbeanstalk.model.DescribeEnvironmentsResult;
import com.amazonaws.services.elasticbeanstalk.model.DescribeEventsRequest;
import com.amazonaws.services.elasticbeanstalk.model.DescribeEventsResult;
import com.amazonaws.services.elasticbeanstalk.model.EnvironmentDescription;
import com.amazonaws.services.elasticbeanstalk.model.EventDescription;
import com.amazonaws.services.elasticbeanstalk.model.S3Location;
import com.amazonaws.services.elasticbeanstalk.model.UpdateEnvironmentRequest;
import com.amazonaws.services.route53.AmazonRoute53Client;
import com.amazonaws.services.s3.AmazonS3Client;

import org.apache.commons.lang.Validate;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import br.com.ingenieux.jenkins.plugins.awsebdeployment.AWSClientFactory;
import br.com.ingenieux.jenkins.plugins.awsebdeployment.Constants;
import br.com.ingenieux.jenkins.plugins.awsebdeployment.Utils;
import edu.umd.cs.findbugs.annotations.SuppressWarnings;
import lombok.Data;
import lombok.experimental.Delegate;

@Data
public class DeployerCommand implements Constants {
    @Delegate
    protected DeployerContext c;

    public void setDeployerContext(DeployerContext c) {
        this.c = c;
    }

    /**
     * Called on start of chain
     *
     * @return false if successful, true if chain must be aborted
     * @throws Exception Returned Exception
     */
    public boolean perform() throws Exception {
        return false;
    }

    /**
     * Called on end of chain
     *
     * @return false if successful, true if chain must be aborted
     * @throws Exception Returned Exception
     */
    public boolean release() throws Exception {
        return false;
    }

    /**
     * Logger Helper
     *
     * @param message message
     * @param args    args
     */
    protected void log(String message, Object... args) {
        getLogger().printf(message, args);
        getLogger().println();
    }

    /**
     * Represents the logger setup
     */
    public static class InitLogger extends DeployerCommand {
        @Override
        public boolean perform() throws Exception {
            /**
             * When Running Remotely, we use loggerOut pipe.
             *
             * Locally, we already set logger instance
             */
            if (null == getLogger())
                setLogger(getListener().getLogger());

            log("AWSEB Deployment Plugin Version %s", Utils.getVersion());

            return false;
        }
    }

    /**
     * Represents the initial validation
     */
    public static class ValidateParameters extends DeployerCommand {
        @Override
        public boolean perform() throws Exception {
            setKeyPrefix(getDeployerConfig().getKeyPrefix());
            setBucketName(getDeployerConfig().getBucketName());
            setApplicationName(getDeployerConfig().getApplicationName());
            setVersionLabel(getDeployerConfig().getVersionLabelFormat());
            setEnvironmentName(getDeployerConfig().getEnvironmentName());
            setEnvironmentCNAMEPrefix(getDeployerConfig().getEnvironmentCNAMEPrefix());
            setEnvironmentTemplateName(getDeployerConfig().getEnvironmentTemplateName());
            setEnvironmentSettings(getDeployerConfig().getEnvironmentSettings());
            setRoute53HostedZoneId(getDeployerConfig().getRoute53HostedZoneId());
            setRoute53DomainNames(getDeployerConfig().getRoute53DomainNames());
            setRoute53RecordTTL(getDeployerConfig().getRoute53RecordTTL());
            setRoute53RecordType(getDeployerConfig().getRoute53RecordType());

            Validate.notEmpty(getEnvironmentName(), "Empty/blank environmentName parameter");
            Validate.notEmpty(getApplicationName(), "Empty/blank applicationName parameter");

            Validate.notEmpty(getVersionLabel(), "Empty/blank versionLabel parameter");

            Validate.isTrue(getRootFileObject().exists(), "Root Object doesn't exist");

            return false;
        }
    }

    /**
     * Builds the AWS Clients altogether
     */
    public static class InitAWS extends DeployerCommand {
        @Override
        public boolean perform() throws Exception {
            AWSClientFactory factory;

            if (null != getDeployerConfig().getCredentials()) {
                factory = AWSClientFactory
                        .getClientFactory(getDeployerConfig().getCredentials(), getDeployerConfig().getAwsRegion());
            } else {
                factory = AWSClientFactory.getClientFactory("", getDeployerConfig().getAwsRegion());
            }

            setS3(factory.getService(AmazonS3Client.class));
            setAwseb(factory.getService(AWSElasticBeanstalkClient.class));
            setRoute53(factory.getService(AmazonRoute53Client.class));

            return false;
        }
    }

    /**
     * Creates an Application Version
     */
    public static class CreateApplicationVersion extends DeployerCommand {
        @Override
        public boolean perform() throws Exception {
            log("Creating application version %s for application %s for path %s",
                    getVersionLabel(), getApplicationName(), getS3ObjectPath());

            CreateApplicationVersionRequest cavRequest = new CreateApplicationVersionRequest()
                    .withApplicationName(getApplicationName())
                    .withAutoCreateApplication(true)
                    .withSourceBundle(new S3Location(getBucketName(), getObjectKey()))
                    .withVersionLabel(getVersionLabel());

            final CreateApplicationVersionResult result = getAwseb().createApplicationVersion(cavRequest);

            log("Created version: %s", result.getApplicationVersion().getVersionLabel());

            return false;
        }
    }

    /**
     * Lookups Environment Id - Aborting Deployment if not found.
     */
    public static class LookupEnvironmentId extends DeployerCommand {
        @Override
        public boolean perform() throws Exception {
            DescribeEnvironmentsRequest req = new DescribeEnvironmentsRequest().
                    withApplicationName(getApplicationName()).
                    withEnvironmentNames(getEnvironmentName()).
                    withIncludeDeleted(false);

            DescribeEnvironmentsResult result = getAwseb().describeEnvironments(req);

            if (1 != result.getEnvironments().size()) {
                log("Unable to lookup environmentId. Skipping Update.");

                return true;
            }

            final EnvironmentDescription environmentDescription = result.getEnvironments().get(0);

            if (environmentDescription.getVersionLabel().equals(getVersionLabel())) {
                log("The version to deploy and currently used are the same. Even if you overwrite, AWSEB won't allow you to update." +
                        "Skipping.");

                return true;
            }

            final String environmentId = environmentDescription.getEnvironmentId();

            log("Using environmentId '%s'", environmentId);

            setEnvironmentId(environmentId);

            return false;
        }
    }

    /**
     * Updates de Application Version
     */
    public static class UpdateApplicationVersion extends DeployerCommand {
        @Override
        public boolean perform() throws Exception {
            UpdateEnvironmentRequest req = new UpdateEnvironmentRequest().
                    withEnvironmentId(getEnvironmentId()).
                    withVersionLabel(getVersionLabel());

            log("Updating environmentId '%s' with Version Label set to '%s'", getEnvironmentId(), getVersionLabel());

            getAwseb().updateEnvironment(req);

            return false;
        }
    }

    /**
     * Waits for the Environment to be Green and Available
     */
    @SuppressWarnings({"EQ_DOESNT_OVERRIDE_EQUALS"})
    public static class WaitForEnvironment extends DeployerCommand {
        final WaitFor waitFor;

        boolean versionCheck;

        public WaitForEnvironment(WaitFor waitFor) {
            this.waitFor = waitFor;
            this.versionCheck = true;
        }

        public WaitForEnvironment withoutVersionCheck() {
            this.versionCheck = false;

            return this;
        }

        @Override
        public boolean perform() throws Exception {
            Long lastMessageTimestamp = System.currentTimeMillis();

            for (int nAttempt = 1; nAttempt <= MAX_ATTEMPTS; nAttempt++) {
                {
                    final DescribeEventsResult describeEventsResult = getAwseb().describeEvents(
                            new DescribeEventsRequest()
                                    .withEnvironmentId(getEnvironmentId())
                                    .withStartTime(new Date(lastMessageTimestamp))
                    );

                    for (EventDescription eventDescription : describeEventsResult.getEvents()) {
                        log("%s [%s] %s", eventDescription.getEventDate(), eventDescription.getSeverity(), eventDescription.getMessage());

                        lastMessageTimestamp = Math.max(eventDescription.getEventDate().getTime(), lastMessageTimestamp);
                    }
                }

                Thread.sleep(TimeUnit.SECONDS.toMillis(SLEEP_TIME));

                log("Checking health/status of environmentId %s attempt %d/%s", getEnvironmentId(), nAttempt,
                        MAX_ATTEMPTS);

                List<EnvironmentDescription> environments =
                        getAwseb().describeEnvironments(new DescribeEnvironmentsRequest()
                                .withEnvironmentIds(
                                        Collections.singletonList(getEnvironmentId()))
                                .withIncludeDeleted(false)
                        ).getEnvironments();

                if (environments.size() != 1) {
                    log("Environment not found. Aborting");

                    return true;
                }

                EnvironmentDescription environmentDescription = environments.get(0);

                // Before checking status and readiness, we must check version unless told otherwise
                {
                    final boolean bHasDifferentVersion = !getVersionLabel().equals(getVersionLabel());

                    if (versionCheck) {
                        log("Versions reported: (current=%s, underDeployment: %s). Should I move on? %s",
                                environmentDescription.getVersionLabel(),
                                getVersionLabel(),
                                String.valueOf(bHasDifferentVersion));
                        if (bHasDifferentVersion) {
                            continue;
                        }
                    }
                }

                final boolean bHealthyP = GREEN_HEALTH.equals(environmentDescription.getHealth());
                final boolean bReadyP = STATUS_READY.equals(environmentDescription.getStatus());

                if (WaitFor.Health == waitFor) {
                    if (bHealthyP) {
                        log("Environment Health is 'Green'. Moving on.");

                        return false;
                    }
                } else if (WaitFor.Status == waitFor) {
                    if (bReadyP) {
                        log("Environment Status is 'Ready'. Moving on.");

                        return false;
                    }
                } else if (WaitFor.Both == waitFor) {
                    if (bReadyP && bHealthyP) {
                        log("Environment Status is 'Ready' and Health is 'Green'. Moving on.");

                        return false;
                    }
                }
            }

            log("Environment Update timed-out. Aborting.");

            return true;
        }

        protected boolean checkVersionLabel(String deployedVersionLabel) throws Exception {
            return getVersionLabel().equals(deployedVersionLabel);
        }
    }

    /**
     * Marks the deployment as successful
     */
    public static class MarkAsSuccessful extends DeployerCommand {
        @Override
        public boolean perform() throws Exception {
            log("Deployment marked as 'successful'. Starting post-deployment cleanup.");

            setSuccessfulP(true);

            return false;
        }
    }

    /**
     * Abort Pending Environment Updates
     */
    public static class AbortPendingUpdates extends DeployerCommand {
        @Override
        public boolean perform() throws Exception {
            final DescribeEnvironmentsRequest req = new DescribeEnvironmentsRequest().
                    withApplicationName(getApplicationName()).
                    withEnvironmentIds(getEnvironmentId()).
                    withIncludeDeleted(false);

            final DescribeEnvironmentsResult result = getAwseb().describeEnvironments(req);

            if (1 != result.getEnvironments().size()) {
                log("Environment w/ environmentId '%s' not found. Aborting.", getEnvironmentId());

                return true;
            }

            String resultingStatus = result.getEnvironments().get(0).getStatus();
            boolean abortableP = result.getEnvironments().get(0).getAbortableOperationInProgress();

            if (!STATUS_READY.equals(resultingStatus)) {
                if (abortableP) {
                    log("AWS Abortable Environment Update Found. Calling abort on AWSEB Service");

                    getAwseb().abortEnvironmentUpdate(new AbortEnvironmentUpdateRequest().withEnvironmentId(getEnvironmentId()));

                    log("Environment Update Aborted. Proceeding.");
                }

                WaitForEnvironment waitForStatus = new WaitForEnvironment(WaitFor.Status).withoutVersionCheck();

                waitForStatus.setDeployerContext(c);

                return waitForStatus.perform();
            } else {
                log("No pending Environment Updates. Proceeding.");
            }

            return false;
        }
    }

}
