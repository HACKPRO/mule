/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.functional.classloading.isolation.classification.aether;

import static org.apache.maven.repository.internal.MavenRepositorySystemUtils.newSession;
import static org.eclipse.aether.repository.RepositoryPolicy.CHECKSUM_POLICY_IGNORE;
import static org.eclipse.aether.repository.RepositoryPolicy.UPDATE_POLICY_NEVER;
import static org.mule.runtime.core.util.Preconditions.checkNotNull;
import org.mule.functional.api.classloading.isolation.WorkspaceLocationResolver;

import com.google.common.collect.Lists;

import java.io.File;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
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
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.resolution.ArtifactDescriptorException;
import org.eclipse.aether.resolution.ArtifactDescriptorRequest;
import org.eclipse.aether.resolution.ArtifactDescriptorResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.file.FileTransporterFactory;
import org.eclipse.aether.util.graph.visitor.PreorderNodeListGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TODO:
 */
public class LocalRepositoryService {

  // TODO: this should be configured!
  public static final String REPOSITORY_MULESOFT_ORG = "repository.mulesoft.org";
  public static final String MULE_PUBLIC_REPO_ID = "mule";

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
  public LocalRepositoryService(List<URL> classpath, WorkspaceLocationResolver workspaceLocationResolver) {
    session = newSession();
    session.setOffline(true);
    session.setUpdatePolicy(UPDATE_POLICY_NEVER);
    session.setChecksumPolicy(CHECKSUM_POLICY_IGNORE);

    system = newRepositorySystem();

    LocalRepository localRepo = createMavenLocalRepository();
    session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, localRepo));
    session.setWorkspaceReader(new DefaultWorkspaceReader(classpath, workspaceLocationResolver));

    session.setRepositoryListener(new LoggerRepositoryListener());
  }

  public static RepositorySystem newRepositorySystem() {
    /*
     * Aether's components implement org.eclipse.aether.spi.locator.Service to ease manual wiring and using the prepopulated
     * DefaultServiceLocator, we only MavenXpp3Readerneed to register the repository connector and transporter factories.
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
   * Resolves transitive dependencies using the filter for the list of direct dependencies by grouping them in an imaginary root
   * node.
   *
   * @param directDependencies {@link List} of direct {@link Dependency} to collect its transitive dependencies
   * @param dependencyFilter {@link DependencyFilter} to include/exclude dependency nodes during collection and resolve operation.
   *        May be {@code null} to no filter
   * @return a {@link List} of {@link File}s for each dependency resolved
   */
  public List<File> resolveDependencies(List<Dependency> directDependencies, DependencyFilter dependencyFilter) {
    checkNotNull(directDependencies, "directDependencies cannot be null");

    return resolveDependencies(null, directDependencies, dependencyFilter);
  }

  /**
   * Resolves direct dependencies for an {@link Artifact}.
   *
   * @param artifact {@link Artifact} to collect its direct dependencies
   * @return a {@link List} of {@link Dependency} for each direct dependency resolved
   */
  public List<Dependency> getDirectDependencies(Artifact artifact) {
    try {
      ArtifactDescriptorResult descriptor =
          system.readArtifactDescriptor(session, new ArtifactDescriptorRequest(artifact,
                                                                               Collections.<RemoteRepository>emptyList(), null));
      return descriptor.getDependencies();
    } catch (ArtifactDescriptorException e) {
      throw new RuntimeException("Error while getting direct dependencies for " + artifact);
    }
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
  private List<File> resolveDependencies(Dependency root, List<Dependency> directDependencies,
                                         DependencyFilter dependencyFilter) {
    CollectRequest collectRequest = new CollectRequest();
    collectRequest.setRoot(root);
    collectRequest.setDependencies(directDependencies);
    collectRequest.setRepositories(getRemoteRepositories());

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

  //TODO (gfernandes) find a way to set this, it should allow to configure a remote repo and its policy
  // By defaul we are just disabling the remote repo (mulesoft public) defined in mule's pom.
  private ArrayList<RemoteRepository> getRemoteRepositories() {
    return Lists.newArrayList(new RemoteRepository.Builder(
                                                           MULE_PUBLIC_REPO_ID,
                                                           "remote",
                                                           REPOSITORY_MULESOFT_ORG)
                                                               .setSnapshotPolicy(
                                                                                  new RepositoryPolicy(false,
                                                                                                       UPDATE_POLICY_NEVER,
                                                                                                       CHECKSUM_POLICY_IGNORE))
                                                               .build());
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
