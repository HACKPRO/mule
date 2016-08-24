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
import org.mule.functional.api.classloading.isolation.MavenMultiModuleArtifactMapping;

import com.google.common.collect.Lists;

import java.io.File;
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

  // TODO: this should be configured!
  public static final String USER_HOME = "user.home";
  private static final String M2_REPO = "/.m2/repository";
  protected final Logger logger = LoggerFactory.getLogger(this.getClass());

  private final DefaultRepositorySystemSession session;
  private final RepositorySystem system;
  private String userHome = System.getProperty(USER_HOME);

  /**
   * Creates an instance of the {@link LocalRepositoryService} to collect Maven dependencies.
   *
   * @param mavenMultiModuleArtifactMapping
   */
  public LocalRepositoryService(MavenMultiModuleArtifactMapping mavenMultiModuleArtifactMapping) {
    session = newSession();
    session.setOffline(true);
    session.setUpdatePolicy(UPDATE_POLICY_NEVER);
    session.setChecksumPolicy(CHECKSUM_POLICY_IGNORE);

    system = newRepositorySystem();

    LocalRepository localRepo = createMavenLocalRepository(userHome);
    session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, localRepo));
    session.setWorkspaceReader(new DefaultWorkspaceReader(mavenMultiModuleArtifactMapping));

    //session.setRepositoryListener(new LoggerRepositoryListener());
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
   * @param dependency {@link Dependency} node from to collect its dependencies
   * @param dependencyFilter {@link DependencyFilter} to include/exclude dependency nodes during collection and resolve operation. May be {@code null} to no filter
   * @return a {@link List} of {@link File}s for each dependency resolved
   */
  public List<File> resolveDependencies(Dependency dependency, DependencyFilter dependencyFilter) {
    CollectRequest collectRequest = new CollectRequest();
    collectRequest.setRoot(dependency);
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
  /**
   * Resolves transitive dependencies using the filter for the list of dependencies by grouping them in an imaginary root node.
   *
   * @param dependencies {@link List} of {@link Dependency} to collect its transitive dependencies
   * @param dependencyFilter {@link DependencyFilter} to include/exclude dependency nodes during collection and resolve operation. May be {@code null} to no filter
   * @return a {@link List} of {@link File}s for each dependency resolved
   */
  public List<File> resolveDependencies(List<Dependency> dependencies, DependencyFilter dependencyFilter) {
    CollectRequest collectRequest = new CollectRequest();
    collectRequest.setDependencies(dependencies);
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

  public List<Dependency> getDirectDependencies(Artifact artifactDescriptorRequest) {
    try {
      ArtifactDescriptorResult descriptor = system.readArtifactDescriptor(session, new ArtifactDescriptorRequest(artifactDescriptorRequest, Collections.<RemoteRepository>emptyList(), null));
      return descriptor.getDependencies();
    } catch (ArtifactDescriptorException e) {
      throw new RuntimeException("Error while getting direct dependencies for " + artifactDescriptorRequest);
    }
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
   * Creates Maven local repository following the default location: {@code $USER_HOME/.m2/repository}
   *
   * @param userHome location of the userHome from system properties
   * @return a {@link LocalRepository} that points to the local m2 repository folder
   */
  private LocalRepository createMavenLocalRepository(String userHome) {
    File mavenLocalRepositoryLocation = new File(userHome, M2_REPO);
    if (!mavenLocalRepositoryLocation.exists()) {
      throw new IllegalArgumentException("Maven repository cannot be supported if it is not located in the default place: <USER_HOME>"
          + M2_REPO);
    }
    return new LocalRepository(mavenLocalRepositoryLocation, "simple");
  }

}
