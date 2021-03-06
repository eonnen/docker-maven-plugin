package org.jolokia.docker.maven;

/*
 * Copyright 2001-2005 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.util.*;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.*;
import org.jolokia.docker.maven.access.DockerAccess;
import org.jolokia.docker.maven.util.EnvUtil;
import org.jolokia.docker.maven.util.PortMapping;

/**
 * Goal for creating and starting a docker container
 *
 * @author roland
 */
@Mojo(name = "start", defaultPhase = LifecyclePhase.PRE_INTEGRATION_TEST)
public class StartMojo extends AbstractDataImageSupportMojo {

    // Port mapping. Can contain symbolic names in which case dynamic
    // ports are used
    @Parameter
    private List<String> ports;

    // Environment variables to set when starting the container. key: variable name, value: env value
    @Parameter
    private Map<String,String> env;

    // Command to execute in container
    @Parameter(property = "docker.command")
    private String command;

    // Path to a file where the dynamically mapped properties are written to
    @Parameter(property = "docker.portPropertyFile")
    private String portPropertyFile;

    // Wait that many milliseconds after starting the container in order to allow the
    // container to warm up
    @Parameter(property = "docker.wait", defaultValue = "0")
    private int wait;

    // Wait until the given URL is accessible
    @Parameter(property = "docker.waitHttp")
    private String waitHttp;

    /** {@inheritDoc} */
    public void executeInternal(DockerAccess docker) throws MojoExecutionException, MojoFailureException {
        if (image == null) {
            throw new MojoFailureException("Image must not be null when using docker:start");
        }
        checkImage(docker,image);

        PortMapping mappedPorts = new PortMapping(ports,project.getProperties());

        String container,dataImage,dataContainer;

        if (useDataContainer()) {
            dataImage = createDataImage(docker);
            if (mergeData) {
                // Image created on the fly and used for action
                dataContainer = null;
                container = docker.createContainer(dataImage,mappedPorts.getContainerPorts(),command,env);
            } else {
                dataContainer = docker.createContainer(dataImage, null, null, env);
                docker.startContainer(dataContainer, null, null);

                container = docker.createContainer(image,mappedPorts.getContainerPorts(),command,env);
            }
        } else {
            dataImage = null;
            dataContainer = null;

            container = docker.createContainer(image,mappedPorts.getContainerPorts(),command,env);
        }

        docker.startContainer(container, mappedPorts.getPortsMap(), dataContainer);
        info("Created and started container " + container.substring(0, 12) + " from image " + (useDataContainer() && mergeData ? dataImage : image));

        // Remember id for later stopping the container
        registerShutdownAction(new ShutdownAction(image,container,dataImage));

        // Set maven properties for dynamically assigned ports.
        if (mappedPorts.containsDynamicPorts()) {
            mappedPorts.updateVarsForDynamicPorts(docker.queryContainerPortMapping(container));
            propagatePortVariables(mappedPorts);
        }

        // Wait if requested
        waitIfRequested(mappedPorts);
    }


    // ========================================================================================================

    private boolean useDataContainer() {
        return assemblyDescriptor != null || assemblyDescriptorRef != null;
    }

    private void waitIfRequested(PortMapping mappedPorts) {
        if (waitHttp != null) {
            String waitUrl = mappedPorts.replaceVars(waitHttp);
            long waited = EnvUtil.httpPingWait(waitUrl, wait);
            info("Waited on " + waitUrl + " for " + waited + " ms");
        } else if (wait > 0) {
            EnvUtil.sleep(wait);
            info("Waited " + wait + " ms");
        }
    }


    // Store dynamically mapped ports
    private void propagatePortVariables(PortMapping mappedPorts) throws MojoExecutionException {
        Properties props = new Properties();
        Map<String,Integer> dynamicPorts = mappedPorts.getDynamicPorts();
        for (Map.Entry<String,Integer> entry : dynamicPorts.entrySet()) {
            String var = entry.getKey();
            String val = "" + entry.getValue();
            project.getProperties().setProperty(var,val);
            props.setProperty(var,val);
        }

        // However, this can be to late since properties in pom.xml are resolved during the "validate" phase
        // (and we are running later probably in "pre-integration" phase. So, in order to bring the dynamically
        // assigned ports to the integration tests a properties file is written. Not nice, but works. Blame it
        // to maven to not allow late evaluation or any other easy way to inter-plugin communication
        if (portPropertyFile != null) {
            EnvUtil.writePortProperties(props, portPropertyFile);
        }
    }

}
