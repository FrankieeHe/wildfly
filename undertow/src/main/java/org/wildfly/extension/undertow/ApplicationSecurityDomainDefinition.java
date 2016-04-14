/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2015, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.wildfly.extension.undertow;

import static io.undertow.UndertowLogger.ROOT_LOGGER;
import static org.jboss.as.controller.capability.RuntimeCapability.buildDynamicCapabilityName;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.servlet.ServletContext;

import org.jboss.as.controller.AbstractAddStepHandler;
import org.jboss.as.controller.AttributeDefinition;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.OperationStepHandler;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.PersistentResourceDefinition;
import org.jboss.as.controller.ResourceDefinition;
import org.jboss.as.controller.ServiceRemoveStepHandler;
import org.jboss.as.controller.SimpleAttributeDefinition;
import org.jboss.as.controller.SimpleAttributeDefinitionBuilder;
import org.jboss.as.controller.StringListAttributeDefinition;
import org.jboss.as.controller.capability.RuntimeCapability;
import org.jboss.as.controller.registry.AttributeAccess;
import org.jboss.as.controller.registry.ManagementResourceRegistration;
import org.jboss.as.controller.registry.Resource;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;
import org.jboss.msc.inject.Injector;
import org.jboss.msc.service.Service;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceController.Mode;
import org.jboss.msc.service.ServiceController.State;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceRegistry;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;
import org.jboss.msc.value.InjectedValue;
import org.wildfly.elytron.web.undertow.server.ElytronContextAssociationHandler;
import org.wildfly.elytron.web.undertow.server.ElytronRunAsHandler;
import org.wildfly.elytron.web.undertow.server.ScopeSessionListener;
import org.wildfly.security.auth.server.HttpAuthenticationFactory;
import org.wildfly.security.http.HttpAuthenticationException;
import org.wildfly.security.http.HttpScope;
import org.wildfly.security.http.HttpServerAuthenticationMechanism;
import org.wildfly.security.http.Scope;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.BlockingHandler;
import io.undertow.servlet.api.AuthMethodConfig;
import io.undertow.servlet.api.DeploymentInfo;
import io.undertow.servlet.api.LoginConfig;
import io.undertow.servlet.handlers.ServletRequestContext;

/**
 * A {@link ResourceDefinition} to define the mapping from a security domain as specified in a web application
 * to an {@link HttpAuthenticationFactory} plus additional policy information.
 *
 * @author <a href="mailto:darran.lofthouse@jboss.com">Darran Lofthouse</a>
 */
public class ApplicationSecurityDomainDefinition extends PersistentResourceDefinition {

    public static final String APPLICATION_SECURITY_DOMAIN_CAPABILITY = "org.wildfly.extension.undertow.application-security-domain";

    static final RuntimeCapability<Void> APPLICATION_SECURITY_DOMAIN_RUNTIME_CAPABILITY = RuntimeCapability
            .Builder.of(APPLICATION_SECURITY_DOMAIN_CAPABILITY, true, Function.class)
            .build();

    private static final String HTTP_AUTHENITCATION_FACTORY_CAPABILITY = "org.wildfly.security.http-server-authentication";

    static SimpleAttributeDefinition HTTP_SERVER_MECHANISM_FACTORY = new SimpleAttributeDefinitionBuilder(Constants.HTTP_AUTHENITCATION_FACTORY, ModelType.STRING, false)
            .setMinSize(1)
            .setFlags(AttributeAccess.Flag.RESTART_RESOURCE_SERVICES)
            .setCapabilityReference(HTTP_AUTHENITCATION_FACTORY_CAPABILITY, APPLICATION_SECURITY_DOMAIN_CAPABILITY, true)
            .build();

    static SimpleAttributeDefinition OVERRIDE_DEPLOYMENT_CONFIG = new SimpleAttributeDefinitionBuilder(Constants.OVERRIDE_DEPLOYMENT_CONFIG, ModelType.BOOLEAN, true)
            .setDefaultValue(new ModelNode(false))
            .setFlags(AttributeAccess.Flag.RESTART_RESOURCE_SERVICES)
            .build();

    private static StringListAttributeDefinition REFERENCING_DEPLOYMENTS = new StringListAttributeDefinition.Builder(Constants.REFERENCING_DEPLOYMENTS)
            .setStorageRuntime()
            .build();

    private static final AttributeDefinition[] ATTRIBUTES = new AttributeDefinition[] { HTTP_SERVER_MECHANISM_FACTORY, OVERRIDE_DEPLOYMENT_CONFIG };

    static final ApplicationSecurityDomainDefinition INSTANCE = new ApplicationSecurityDomainDefinition();

    private static final Set<String> knownApplicationSecurityDomains = Collections.synchronizedSet(new HashSet<>());

    private ApplicationSecurityDomainDefinition() {
        this(new Parameters(PathElement.pathElement(Constants.APPLICATION_SECURITY_DOMAIN), UndertowExtension.getResolver(Constants.APPLICATION_SECURITY_DOMAIN))
                .setCapabilities(APPLICATION_SECURITY_DOMAIN_RUNTIME_CAPABILITY), new AddHandler());
    }

    private ApplicationSecurityDomainDefinition(Parameters parameters, AbstractAddStepHandler add) {
        super(parameters.setAddHandler(add).setRemoveHandler(new RemoveHandler(add)));
    }

    @Override
    public void registerAttributes(ManagementResourceRegistration resourceRegistration) {
        super.registerAttributes(resourceRegistration);
        resourceRegistration.registerReadOnlyAttribute(REFERENCING_DEPLOYMENTS, new ReferencingDeploymentsHandler());
    }

    private static class ReferencingDeploymentsHandler implements OperationStepHandler {

        @Override
        public void execute(OperationContext context, ModelNode operation) throws OperationFailedException {
            RuntimeCapability<Void> runtimeCapability = APPLICATION_SECURITY_DOMAIN_RUNTIME_CAPABILITY.fromBaseCapability(context.getCurrentAddressValue());
            ServiceName applicationSecurityDomainName = runtimeCapability.getCapabilityServiceName(Function.class);

            ServiceRegistry serviceRegistry = context.getServiceRegistry(false);
            ServiceController<?> controller = serviceRegistry.getRequiredService(applicationSecurityDomainName);

            ModelNode deploymentList = new ModelNode();
            if (controller.getState() == State.UP) {
                Service service = controller.getService();
                if (service instanceof ApplicationSecurityDomainService) {
                    for (String current : ((ApplicationSecurityDomainService)service).getDeployments()) {
                        deploymentList.add(current);
                    }
                }
            }

            context.getResult().set(deploymentList);
        }

    }

    private static class AddHandler extends AbstractAddStepHandler {

        private AddHandler() {
            super(ATTRIBUTES);
        }

        /* (non-Javadoc)
         * @see org.jboss.as.controller.AbstractAddStepHandler#populateModel(org.jboss.as.controller.OperationContext, org.jboss.dmr.ModelNode, org.jboss.as.controller.registry.Resource)
         */
        @Override
        protected void populateModel(OperationContext context, ModelNode operation, Resource resource) throws OperationFailedException {
            super.populateModel(context, operation, resource);
            knownApplicationSecurityDomains.add(context.getCurrentAddressValue());
        }

        @Override
        protected void performRuntime(OperationContext context, ModelNode operation, ModelNode model) throws OperationFailedException {

            String httpServerMechanismFactory = HTTP_SERVER_MECHANISM_FACTORY.resolveModelAttribute(context, model).asString();
            boolean overrideDeploymentConfig = OVERRIDE_DEPLOYMENT_CONFIG.resolveModelAttribute(context, model).asBoolean();

            RuntimeCapability<?> runtimeCapability = APPLICATION_SECURITY_DOMAIN_RUNTIME_CAPABILITY.fromBaseCapability(context.getCurrentAddressValue());
            ServiceName serviceName = runtimeCapability.getCapabilityServiceName(Function.class);

            ApplicationSecurityDomainService applicationSecurityDomainService = new ApplicationSecurityDomainService(overrideDeploymentConfig);

            ServiceBuilder<Function<DeploymentInfo, Registration>> serviceBuilder = context.getServiceTarget().addService(serviceName, applicationSecurityDomainService)
                    .setInitialMode(Mode.LAZY);

            serviceBuilder.addDependency(context.getCapabilityServiceName(
                    buildDynamicCapabilityName(HTTP_AUTHENITCATION_FACTORY_CAPABILITY, httpServerMechanismFactory), HttpAuthenticationFactory.class),
                    HttpAuthenticationFactory.class, applicationSecurityDomainService.getHttpAuthenticationFactoryInjector());

            serviceBuilder.install();
        }

    }

    private static class RemoveHandler extends ServiceRemoveStepHandler {

        /**
         * @param addOperation
         */
        protected RemoveHandler(AbstractAddStepHandler addOperation) {
            super(addOperation);
        }

        @Override
        protected void performRemove(OperationContext context, ModelNode operation, ModelNode model) throws OperationFailedException {
            super.performRemove(context, operation, model);
            knownApplicationSecurityDomains.remove(context.getCurrentAddressValue());
        }

        @Override
        protected ServiceName serviceName(String name) {
            RuntimeCapability<?> dynamicCapability = APPLICATION_SECURITY_DOMAIN_RUNTIME_CAPABILITY.fromBaseCapability(name);
            return dynamicCapability.getCapabilityServiceName(HttpAuthenticationFactory.class);
        }

    }

    @Override
    public Collection<AttributeDefinition> getAttributes() {
        return Arrays.asList(ATTRIBUTES);
    }

    Predicate<String> getKnownSecurityDomainPredicate() {
        return knownApplicationSecurityDomains::contains;
    }

    private static class ApplicationSecurityDomainService implements Service<Function<DeploymentInfo, Registration>> {

        private final boolean overrideDeploymentConfig;
        private final InjectedValue<HttpAuthenticationFactory> httpAuthenticationFactoryInjector = new InjectedValue<>();
        private final Set<RegistrationImpl> registrations = new HashSet<>();

        private HttpAuthenticationFactory httpAuthenticationFactory;

        private ApplicationSecurityDomainService(final boolean overrideDeploymentConfig) {
            this.overrideDeploymentConfig = overrideDeploymentConfig;
        }

        @Override
        public void start(StartContext context) throws StartException {
            httpAuthenticationFactory = httpAuthenticationFactoryInjector.getValue();
        }

        @Override
        public void stop(StopContext context) {
            httpAuthenticationFactory = null;
        }

        @Override
        public Function<DeploymentInfo, Registration> getValue() throws IllegalStateException, IllegalArgumentException {
            return this::applyElytronSecurity;
        }

        private Injector<HttpAuthenticationFactory> getHttpAuthenticationFactoryInjector() {
            return httpAuthenticationFactoryInjector;
        }

        private Registration applyElytronSecurity(final DeploymentInfo deploymentInfo) {
            final ScopeSessionListener scopeSessionListener = ScopeSessionListener.builder()
                    .addScopeResolver(Scope.APPLICATION, ApplicationSecurityDomainService::applicationScope)
                    .build();
            deploymentInfo.addSessionListener(scopeSessionListener);

            deploymentInfo.addInnerHandlerChainWrapper(this::finalSecurityHandlers);
            deploymentInfo.setInitialSecurityWrapper(h -> initialSecurityHandler(deploymentInfo, h, scopeSessionListener));

            RegistrationImpl registration = new RegistrationImpl(deploymentInfo);
            synchronized(registrations) {
                registrations.add(registration);
            }
            return registration;
        }

        private List<String> desiredMechanisms(DeploymentInfo deploymentInfo) {
            if (overrideDeploymentConfig) {
                return new ArrayList<>(httpAuthenticationFactory.getMechanismNames());
            } else {
                final LoginConfig loginConfig = deploymentInfo.getLoginConfig();
                final List<AuthMethodConfig> authMethods = loginConfig == null ? Collections.<AuthMethodConfig>emptyList() : loginConfig.getAuthMethods();
                return authMethods.stream().map(c -> c.getName())
                        .collect(Collectors.toList());
            }
        }

        private HttpServerAuthenticationMechanism createMechanism(final String name) {
            try {
                return httpAuthenticationFactory.createMechanism(name);
            } catch (HttpAuthenticationException e) {
                throw new IllegalStateException(e);
            }
        }
        private List<HttpServerAuthenticationMechanism> getAuthenticationMechanisms(Supplier<List<String>> mechanismNames) {
            return mechanismNames.get().stream().map(this::createMechanism).collect(Collectors.toList());
        }

        private InputStream getResource(DeploymentInfo deploymentInfo, String path) {
            try {
                io.undertow.server.handlers.resource.Resource resource = deploymentInfo.getResourceManager().getResource(path);
                if (resource != null) {
                    File file = resource.getFile();
                    if (file != null) {
                        return new FileInputStream(file);
                    }
                }
            } catch (IOException e) {
                ROOT_LOGGER.debug(e);
            }
            return null;
        }

        private HttpHandler initialSecurityHandler(final DeploymentInfo deploymentInfo, HttpHandler toWrap, ScopeSessionListener scopeSessionListener) {
            return ElytronContextAssociationHandler.builder()
                    .setNext(toWrap)
                    .setMechanismSupplier(() -> getAuthenticationMechanisms(() -> desiredMechanisms(deploymentInfo)))
                    .addScopeResolver(Scope.APPLICATION, ApplicationSecurityDomainService::applicationScope)
                    .setScopeSessionListener(scopeSessionListener)
                    .build();
        }

        private static HttpScope applicationScope(HttpServerExchange exchange) {
            ServletRequestContext servletRequestContext = exchange.getAttachment(ServletRequestContext.ATTACHMENT_KEY);

            if (servletRequestContext != null) {
                final ServletContext servletContext = servletRequestContext.getDeployment().getServletContext();
                return new HttpScope() {

                    @Override
                    public boolean supportsAttachments() {
                        return true;
                    }

                    @Override
                    public void setAttachment(String key, Object value) {
                        servletContext.setAttribute(key, value);
                    }

                    @Override
                    public Object getAttachment(String key) {
                        return servletContext.getAttribute(key);
                    }

                    @Override
                    public boolean supportsResources() {
                        return true;
                    }

                    @Override
                    public InputStream getResource(String path) {
                        return servletContext.getResourceAsStream(path);
                    }


                };
            }

            return null;
        }

        private HttpHandler finalSecurityHandlers(HttpHandler toWrap) {
            return new BlockingHandler(new ElytronRunAsHandler(toWrap));
        }

        private String[] getDeployments() {
            synchronized(registrations) {
                return registrations.stream().map(r -> r.deploymentInfo.getDeploymentName()).collect(Collectors.toList()).toArray(new String[registrations.size()]);
            }
        }

        private class RegistrationImpl implements Registration {

            private final DeploymentInfo deploymentInfo;

            private RegistrationImpl(DeploymentInfo deploymentInfo) {
                this.deploymentInfo = deploymentInfo;
            }

            @Override
            public void cancel() {
                synchronized(registrations) {
                    registrations.remove(this);
                }
            }

        }

    }

    public interface Registration {

        /**
         * Cancel the registration.
         */
        void cancel();

    }
}
