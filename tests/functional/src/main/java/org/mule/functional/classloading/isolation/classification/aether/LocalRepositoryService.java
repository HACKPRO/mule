/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.functional.classloading.isolation.classification.aether;

import static java.lang.Boolean.valueOf;
import static java.lang.System.getProperty;
import static org.apache.maven.repository.internal.MavenRepositorySystemUtils.newSession;
import static org.eclipse.aether.repository.RepositoryPolicy.CHECKSUM_POLICY_IGNORE;
import static org.eclipse.aether.repository.RepositoryPolicy.UPDATE_POLICY_NEVER;
import static org.mule.runtime.core.api.config.MuleProperties.MULE_LOG_VERBOSE_CLASSLOADING;
import static org.mule.runtime.core.util.Preconditions.checkNotNull;
import org.mule.functional.api.classloading.isolation.WorkspaceLocationResolver;

import java.io.File;
import java.net.URL;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyFilter;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactDescriptorException;
import org.eclipse.aether.resolution.ArtifactDescriptorRequest;
import org.eclipse.aether.resolution.ArtifactDescriptorResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.file.FileTransporterFactory;
import org.eclipse.aether.util.graph.selector.AndDependencySelector;
import org.eclipse.aether.util.graph.visitor.PreorderNodeListGenerator;
import org.eclipse.aether.util.repository.SimpleArtifactDescriptorPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TODO:
 */
public class LocalRepositoryService {

  public static final String USER_HOME = "user.home";
  public static final String M2_REPO = "/.m2/repository";

  protected final Logger logger = LoggerFactory.getLogger(this.getClass());

  private final DefaultRepositorySystemSession session;
  private final RepositorySystem system;
  private String userHome = System.getProperty(USER_HOME);

  /**
   * Creates an instance of the {@link LocalRepositoryService} to collect Maven dependencies.
   *
   * @param workspaceLocationResolver {@link WorkspaceLocationResolver} to resolve artifactId's {@link Path}s from workspace.
   */
  public LocalRepositoryService(List<URL> classPath, WorkspaceLocationResolver workspaceLocationResolver) {
    session = newSession();
    session.setOffline(true);
    session.setUpdatePolicy(UPDATE_POLICY_NEVER);
    session.setChecksumPolicy(CHECKSUM_POLICY_IGNORE);
    session.setArtifactDescriptorPolicy(new SimpleArtifactDescriptorPolicy(true, true));
    //TODO (gfernandes) Do we want to allow remote repositories to be accessed during resolution?
    session.setIgnoreArtifactDescriptorRepositories(true);
    session
        .setDependencySelector(new AndDependencySelector(session.getDependencySelector(),
                                                         new WorkspaceDependencySelector(classPath, workspaceLocationResolver)));

    system = newRepositorySystem();

    LocalRepository localRepo = createMavenLocalRepository();
    session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, localRepo));
    session.setWorkspaceReader(new DefaultWorkspaceReader(classPath, workspaceLocationResolver));

    if (valueOf(getProperty(MULE_LOG_VERBOSE_CLASSLOADING))) {
      session.setRepositoryListener(new LoggerRepositoryListener());
    }
  }

  /**
   * Creates and configures the {@link RepositorySystem} to use for resolving transitive dependencies.
   *
   * @return {@link RepositorySystem}
   */
  private static RepositorySystem newRepositorySystem() {
    /*
     * Aether's components implement org.eclipse.aether.spi.locator.Service to ease manual wiring and using the pre populated
     * DefaultServiceLocator, we only MavenXpp3Reader need to register the repository connector and transporter factories.
     */
    DefaultServiceLocator locator = MavenRepositorySystemUtils.newServiceLocator();
    locator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
    locator.addService(TransporterFactory.class, FileTransporterFactory.class);

    locator.setErrorHandler(new DefaultServiceLocator.ErrorHandler() {

      @Override
      public void serviceCreationFailed(Class<?> type, Class<?> impl, Throwable exception) {
        exception.printStackTrace();
      }
    });

    return locator.getService(RepositorySystem.class);
  }

  /**
   * Gets information about an artifact like its direct dependencies and potential relocations.
   *
   * @param artifact the artifact requested, must not be {@code null}
   * @return {@link ArtifactDescriptorResult} descriptor result, never {@code null}
   * @throws {@link RuntimeException} if there was a problem while reading the descriptor
   */
  public ArtifactDescriptorResult readArtifactDescriptor(Artifact artifact) {
    try {
      ArtifactDescriptorResult descriptor =
          system.readArtifactDescriptor(session, new ArtifactDescriptorRequest(artifact,
                                                                               Collections.<RemoteRepository>emptyList(), null));
      return descriptor;
    } catch (ArtifactDescriptorException e) {
      throw new IllegalStateException("Couldn't read descriptor for artifact: '" + artifact
          + "', it has to be able to be resolved through the workspace or installed in your local Maven respository");
    }
  }

  /**
   * Resolves direct dependencies for an {@link Artifact}.
   *
   * @param artifact {@link Artifact} to collect its direct dependencies
   * @return a {@link List} of {@link Dependency} for each direct dependency resolved
   */
  public List<Dependency> getDirectDependencies(Artifact artifact) {
    checkNotNull(artifact, "artifact cannot be null");
    return readArtifactDescriptor(artifact).getDependencies();
  }

  /**
   * Resolves transitive dependencies for the dependency as root node using the filter.
   *
   * @param root {@link Dependency} node from to collect its dependencies
   * @param dependencyFilter {@link DependencyFilter} to include/exclude dependency nodes during collection and resolve operation.
   *        May be {@code null} to no filter
   * @return a {@link List} of {@link File}s for each dependency resolved
   */
  public List<File> resolveDependencies(Dependency root, DependencyFilter dependencyFilter) {
    checkNotNull(root, "root cannot be null");
    return resolveDependencies(root, Collections.<Dependency>emptyList(), dependencyFilter);
  }

  /**
   * Resolves and filters transitive dependencies for the root and direct dependencies.
   * <p/>
   * If both a root dependency and direct dependencies are given, the direct dependencies will be merged with the direct
   * dependencies from the root dependency's artifact descriptor, giving higher priority to the dependencies from the root.
   *
   * @param root {@link Dependency} node from to collect its dependencies
   * @param directDependencies {@link List} of direct {@link Dependency} to collect its transitive dependencies
   * @param dependencyFilter {@link DependencyFilter} to include/exclude dependency nodes during collection and resolve operation.
   *        May be {@code null} to no filter
   * @return a {@link List} of {@link File}s for each dependency resolved
   */
  public List<File> resolveDependencies(Dependency root, List<Dependency> directDependencies,
                                        DependencyFilter dependencyFilter) {
    CollectRequest collectRequest = new CollectRequest();
    collectRequest.setRoot(root);
    collectRequest.setDependencies(directDependencies);
    collectRequest.setRepositories(Collections.<RemoteRepository>emptyList());

    DependencyNode node;
    try {
      node = system.collectDependencies(session, collectRequest).getRoot();
      DependencyRequest dependencyRequest = new DependencyRequest();
      dependencyRequest.setRoot(node);
      dependencyRequest.setCollectRequest(collectRequest);
      if (dependencyFilter != null) {
        dependencyRequest.setFilter(dependencyFilter);
      }

      node = system.resolveDependencies(session, dependencyRequest).getRoot();
    } catch (DependencyCollectionException | DependencyResolutionException e) {
      throw new RuntimeException("Error while resolving dependencies", e);
    }

    List<File> files = getFiles(node);
    return files;
  }

  /**
   * Traverse the {@link DependencyNode} to get the files for each artifact.
   *
   * @param node {@link DependencyNode} that represents the dependency graph
   * @return {@link List} of {@link File}s for each artifact resolved
   */
  private List<File> getFiles(DependencyNode node) {
    PreorderNodeListGenerator nlg = new PreorderNodeListGenerator();
    node.accept(nlg);

    return nlg.getFiles().stream().map(File::getAbsoluteFile).collect(Collectors.toList());
  }

  /**
   * Creates Maven local repository using the {@link System#getProperty(String)} {@code localRepository} or following the default
   * location: {@code $USER_HOME/.m2/repository} if no property set.
   *
   * @return a {@link LocalRepository} that points to the local m2 repository folder
   */
  private LocalRepository createMavenLocalRepository() {
    String localRepositoryProperty = System.getProperty("localRepository");
    if (localRepositoryProperty == null) {
      localRepositoryProperty = userHome + M2_REPO;
      logger.debug("System property 'localRepository' not set, using Maven default location");
    }

    logger.debug("Using Maven localRepository: '{}'", localRepositoryProperty);
    File mavenLocalRepositoryLocation = new File(localRepositoryProperty);
    if (!mavenLocalRepositoryLocation.exists()) {
      throw new IllegalArgumentException("Maven repository location couldn't be found, please check your configuration");
    }
    // We have to set to use a "simple" aether local repository so it will not cache artifacts (enhanced is supported for doing
    // operations such install).
    return new LocalRepository(mavenLocalRepositoryLocation, "simple");
  }

}
