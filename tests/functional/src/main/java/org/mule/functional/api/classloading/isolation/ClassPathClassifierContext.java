/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.functional.api.classloading.isolation;

import static org.mule.functional.classloading.isolation.utils.RunnerModuleUtils.EXCLUDED_PROPERTIES_FILE;
import static org.mule.functional.classloading.isolation.utils.RunnerModuleUtils.getExcludedProperties;
import static org.mule.runtime.core.util.Preconditions.checkNotNull;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

/**
 * Represents a context that contains what is needed in order to do a classpath classification. It is used in
 * {@link ClassPathClassifier}.
 *
 * @since 4.0
 */
public class ClassPathClassifierContext {

  public static final String EXCLUDED_ARTIFACTS = "excluded.artifacts";
  public static final String EXTRA_BOOT_PACKAGES = "extraBoot.packages";

  protected final Logger logger = LoggerFactory.getLogger(this.getClass());

  private final File rootArtifactClassesFolder;
  private final File rootArtifactTestClassesFolder;
  private final List<URL> classPathURLs;
  private final WorkspaceLocationResolver workspaceLocationResolver;
  private final List<String> providedExclusions;
  private final List<String> excludedArtifacts = Lists.newArrayList();
  private final List<String> applicationArtifactExclusionsCoordinates;
  private final Set<String> extraBootPackages;
  private final List<String> pluginCoordinates;
  private final Set<Class> exportPluginClasses;

  /**
   * Creates a context used for doing the classification of the class path.
   *
   * @param rootArtifactClassesFolder {@link File} to the target/classes of the current artifact being classified. Not null.
   * @param rootArtifactTestClassesFolder {@link File} to the target/test-classes of the current artifact being classified. Not
   *        null.
   * @param classPathURLs the whole set of {@link URL}s that were loaded by IDE/Maven Surefire plugin when running the test. Not
   *        null.
   * @param workspaceLocationResolver {@link WorkspaceLocationResolver} for artifactIds. Not null.
   * @param providedExclusions Maven artifacts to be excluded from the provided scope direct dependencies of rootArtifact. In
   *        format {@code <groupId>:<artifactId>:<extension>:<version>}.
   * @param applicationArtifactExclusionsCoordinates {@link List} of Maven coordinates to be excluded from application class
   *        loader.
   * @param extraBootPackagesList {@link List} of {@link String}'s packages to be added as boot packages to the container.
   * @param pluginCoordinates {@link List} of Maven coordinates in format {@code <groupId>:<artifactId>} in order to create plugin
   *        {@link org.mule.runtime.module.artifact.classloader.ArtifactClassLoader}s
   * @param exportPluginClasses {@link Set} of {@link Class} to be exported in addition to the ones already exported by the
   *        plugin, for testing purposes only.
   * @throws IOException if an error happened while reading
   *         {@link org.mule.functional.classloading.isolation.utils.RunnerModuleUtils#EXCLUDED_PROPERTIES_FILE} file
   */
  public ClassPathClassifierContext(final File rootArtifactClassesFolder, final File rootArtifactTestClassesFolder,
                                    final List<URL> classPathURLs,
                                    final WorkspaceLocationResolver workspaceLocationResolver,
                                    final List<String> providedExclusions,
                                    final List<String> applicationArtifactExclusionsCoordinates,
                                    final List<String> extraBootPackagesList,
                                    final List<String> pluginCoordinates,
                                    final Set<Class> exportPluginClasses)
      throws IOException {
    checkNotNull(rootArtifactClassesFolder, "rootArtifactClassesFolder cannot be null");
    checkNotNull(rootArtifactTestClassesFolder, "rootArtifactTestClassesFolder cannot be null");
    checkNotNull(classPathURLs, "classPathURLs cannot be null");
    checkNotNull(workspaceLocationResolver, "workspaceLocationResolver cannot be null");

    this.rootArtifactClassesFolder = rootArtifactClassesFolder;
    this.rootArtifactTestClassesFolder = rootArtifactTestClassesFolder;
    this.classPathURLs = classPathURLs;
    this.workspaceLocationResolver = workspaceLocationResolver;
    this.providedExclusions = providedExclusions;

    Properties excludedProperties = getExcludedProperties();
    String excludedArtifacts = excludedProperties.getProperty(EXCLUDED_ARTIFACTS);
    if (excludedArtifacts != null) {
      for (String exclusion : excludedArtifacts.split(",")) {
        this.excludedArtifacts.add(exclusion);
      }
    }
    this.applicationArtifactExclusionsCoordinates = applicationArtifactExclusionsCoordinates;
    this.extraBootPackages = getExtraBootPackages(extraBootPackagesList, excludedProperties);

    this.exportPluginClasses = exportPluginClasses;

    this.pluginCoordinates = pluginCoordinates;
  }

  /**
   * @return a {@link File} to the classes of the current artifact being tested.
   */
  public File getRootArtifactClassesFolder() {
    return rootArtifactClassesFolder;
  }

  /**
   * @return a {@link File} to the test classes of the current artifact being tested.
   */
  public File getRootArtifactTestClassesFolder() {
    return rootArtifactTestClassesFolder;
  }

  /**
   * @return a {@link List} of {@link URL}s for the classpath provided by JUnit (it is the complete list of URLs)
   */
  public List<URL> getClassPathURLs() {
    return classPathURLs;
  }

  /**
   * @return {@link WorkspaceLocationResolver} resolver for artifactIds folders.
   */
  public WorkspaceLocationResolver getWorkspaceLocationResolver() {
    return workspaceLocationResolver;
  }

  /**
   * @return Maven artifacts to be excluded from the {@code provided} scope direct dependencies of the rootArtifact. In format
   *         {@code <groupId>:<artifactId>:<extension>:<version>}.
   */
  public List<String> getProvidedExclusions() {
    return this.providedExclusions;
  }

  /**
   * @return Maven artifacts to be excluded from the Mule container artifact when resolving dependencies. In format
   *         {@code <groupId>:<artifactId>:[extension]:<version>}.
   */
  public List<String> getExcludedArtifacts() {
    return this.excludedArtifacts;
  }

  /**
   * Artifacts to be excluded from being added to application {@link ClassLoader} due to they are going to be in container
   * {@link ClassLoader}.
   * 
   * @return {@link Set} of Maven coordinates in the format:
   * 
   *         <pre>
   * <groupId>:<artifactId>:<extension>:<version>
   *         </pre>
   */
  public List<String> getApplicationArtifactExclusionsCoordinates() {
    return applicationArtifactExclusionsCoordinates;
  }

  /**
   * @return {@link Set} of {@link String}s containing the extra boot packages defined to be appended to the container in addition
   *         to the pre-defined ones.
   */
  public Set<String> getExtraBootPackages() {
    return extraBootPackages;
  }

  /**
   * @return {@link Set} of {@link Class}es that are going to be exported in addition to the ones already exported by plugins. For
   *         testing purposes only.
   */
  public Set<Class> getExportPluginClasses() {
    return exportPluginClasses;
  }

  /**
   * @return {@link List} of plugins as {@code [groupId]:[artifactId]} format for creates plugin
   *         {@link org.mule.runtime.module.artifact.classloader.ArtifactClassLoader}
   */
  public List<String> getPluginCoordinates() {
    return pluginCoordinates;
  }

  /**
   * Gets the {@link Set} of {@link String}s of packages to be added to the container {@link ClassLoader} in addition to the ones
   * already pre-defined by the mule container.
   *
   * @param excludedProperties {@link Properties }that has the list of extra boot packages definitions
   * @return a {@link Set} of {@link String}s with the extra boot packages to be appended
   */
  private Set<String> getExtraBootPackages(final List<String> extraBootPackagesList, final Properties excludedProperties) {
    Set<String> packages = Sets.newHashSet();
    packages.addAll(extraBootPackagesList);

    String excludedExtraBootPackages = excludedProperties.getProperty(EXTRA_BOOT_PACKAGES);
    if (excludedExtraBootPackages != null) {
      for (String extraBootPackage : excludedExtraBootPackages.split(",")) {
        packages.add(extraBootPackage);
      }
    } else {
      logger.warn(EXCLUDED_PROPERTIES_FILE
          + " found but there is no list of extra boot packages defined to be added to container, this could be the reason why the test may fail later due to JUnit classes are not found");
    }
    return packages;
  }

}
