/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.functional.classloading.isolation.classification;

import static java.util.stream.Collectors.toList;
import static org.eclipse.aether.util.artifact.JavaScopes.COMPILE;
import static org.eclipse.aether.util.artifact.JavaScopes.PROVIDED;
import static org.eclipse.aether.util.artifact.JavaScopes.TEST;
import static org.eclipse.aether.util.filter.DependencyFilterUtils.classpathFilter;
import static org.eclipse.aether.util.filter.DependencyFilterUtils.orFilter;
import org.mule.functional.api.classloading.isolation.ArtifactUrlClassification;
import org.mule.functional.api.classloading.isolation.ClassPathClassifier;
import org.mule.functional.api.classloading.isolation.ClassPathClassifierContext;
import org.mule.functional.api.classloading.isolation.PluginUrlClassification;
import org.mule.functional.classloading.isolation.classification.aether.LocalRepositoryService;
import org.mule.functional.classloading.isolation.classification.aether.PatternInclusionsDependencyFilter;
import org.mule.functional.classloading.isolation.maven.MavenModelFactory;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import java.io.File;
import java.io.FileFilter;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collection;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Optional;

import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.apache.maven.model.Model;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyFilter;
import org.eclipse.aether.graph.Exclusion;
import org.eclipse.aether.resolution.ArtifactDescriptorResult;
import org.eclipse.aether.util.filter.PatternExclusionsDependencyFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TODO
 */
public class AetherClassPathClassifier implements ClassPathClassifier {

  // TODO: this should be configured!
  public static final String MULE_STANDALONE_ARTIFACT =
      "org.mule.distributions:mule-standalone:pom:4.0-SNAPSHOT";
  public static final String ORG_MULE_TESTS_GROUP_ID = "org.mule.tests";
  public static final String ORG_MULE_EXTENSIONS_GROUP_ID = "org.mule.extensions";
  public static final String MULE_EXTENSIONS_ALL_ARTIFACT_ID = "mule-extensions-all";
  public static final String ALL_TESTS_JAR_ARTIFACT_COORDS = "*:*:jar:tests:*";

  protected final Logger logger = LoggerFactory.getLogger(this.getClass());

  @Override
  public ArtifactUrlClassification classify(ClassPathClassifierContext context) {
    LocalRepositoryService localRepositoryService =
        new LocalRepositoryService(context.getClassPathURLs(), context.getWorkspaceLocationResolver());

    Artifact rootArtifact = getRootArtifact(context);
    List<Dependency> directDependencies = localRepositoryService
        .getDirectDependencies(rootArtifact);

    List<URL> containerUrls = buildContainerUrlClassification(localRepositoryService, context);
    List<PluginUrlClassification> pluginUrlClassifications =
        buildPluginUrlClassifications(context, rootArtifact, directDependencies, localRepositoryService);
    List<URL> applicationUrls = buildApplicationUrlClassification(context, rootArtifact, directDependencies,
                                                                  localRepositoryService, pluginUrlClassifications);
    List<URL> bootLauncherUrls = getBootLauncherURLs(context);

    return new ArtifactUrlClassification(bootLauncherUrls, containerUrls, pluginUrlClassifications,
                                         applicationUrls);
  }

  private Artifact getRootArtifact(ClassPathClassifierContext context) {
    File pomFile = new File(context.getRootArtifactClassesFolder().getParentFile().getParentFile(), "/pom.xml");
    Model model = MavenModelFactory.createMavenProject(pomFile);

    return new DefaultArtifact(model.getGroupId() != null ? model.getGroupId() : model.getParent().getGroupId(),
                               model.getArtifactId(), model.getPackaging(),
                               model.getVersion() != null ? model.getVersion() : model.getParent().getVersion());
  }

  private List<URL> getBootLauncherURLs(ClassPathClassifierContext context) {
    Optional<URL> firstArtifactURL = context.getClassPathURLs().stream()
        .filter(
                url -> context.getRootArtifactTestClassesFolder().getAbsolutePath()
                    .equals(new File(url.getFile()).getAbsolutePath()))
        .findFirst();
    if (!firstArtifactURL.isPresent()) {
      throw new IllegalStateException("Couldn't get Boot/Launcher URLs from classpath");
    }

    if (logger.isDebugEnabled()) {
      logger.debug("First URL for artifact found in classpath: " + firstArtifactURL.get());
    }
    return context.getClassPathURLs().subList(0, context.getClassPathURLs().indexOf(firstArtifactURL.get()));
  }

  private List<URL> buildApplicationUrlClassification(ClassPathClassifierContext context,
                                                      Artifact rootArtifact,
                                                      List<Dependency> directDependencies,
                                                      LocalRepositoryService localRepositoryService,
                                                      List<PluginUrlClassification> pluginUrlClassifications) {
    List<File> applicationFiles = Lists.newArrayList(context.getRootArtifactTestClassesFolder());

    directDependencies = directDependencies.stream()
        .map(toTransform -> {
          if (toTransform.getScope().equals(TEST)) {
            return new Dependency(toTransform.getArtifact(), COMPILE);
          }
          return toTransform;
        }).collect(toList());
    DependencyFilter dependencyFilter = new PatternInclusionsDependencyFilter(
                                                                              ALL_TESTS_JAR_ARTIFACT_COORDS);
    if (!context.getApplicationArtifactExclusionsCoordinates().isEmpty()) {
      dependencyFilter = orFilter(new PatternExclusionsDependencyFilter(context.getApplicationArtifactExclusionsCoordinates()),
                                  dependencyFilter);
    }

    boolean isRootArtifactPlugin = !pluginUrlClassifications.isEmpty()
        && pluginUrlClassifications.stream().filter(p -> {
          Artifact plugin = new DefaultArtifact(p.getName());
          return plugin.getGroupId().equals(rootArtifact.getGroupId())
              && plugin.getArtifactId().equals(rootArtifact.getArtifactId());
        }).findFirst().isPresent();
    if (!isRootArtifactPlugin) {
      if (context.getRootArtifactClassesFolder().exists()) {
        applicationFiles.add(context.getRootArtifactClassesFolder());
      } else {
        logger.debug("'{}' rootArtifact marked as a test artifact only, it doesn't have a target/classes folder", rootArtifact);
        dependencyFilter = orFilter(new PatternExclusionsDependencyFilter(rootArtifact.toString()));
      }
    }

    applicationFiles
        .addAll(localRepositoryService.resolveDependencies(new Dependency(rootArtifact, TEST), directDependencies,
                                                           dependencyFilter));

    return toUrl(applicationFiles);
  }

  private List<PluginUrlClassification> buildPluginUrlClassifications(ClassPathClassifierContext context,
                                                                      Artifact rootArtifact,
                                                                      List<Dependency> directDependencies,
                                                                      LocalRepositoryService localRepositoryService) {
    List<PluginUrlClassification> pluginUrlClassifications = Lists.newArrayList();
    if (context.getPluginCoordinates() != null) {
      for (String pluginCoords : context.getPluginCoordinates()) {
        logger.debug("Resolving plugin coordinates: '{}'", pluginCoords);

        final String[] pluginSplitCoords = pluginCoords.split(":");
        String pluginGroupId = pluginSplitCoords[0];
        String pluginArtifactId = pluginSplitCoords[1];
        String pluginVersion;

        if (rootArtifact.getGroupId().equals(pluginGroupId) && rootArtifact.getArtifactId().equals(pluginArtifactId)) {
          logger.debug("'{}' declared as plugin, resolving version from pom file", rootArtifact);
          pluginVersion = rootArtifact.getVersion();
        } else {
          logger.debug("Resolving version for '{}' from direct dependencies", pluginCoords);
          Optional<Dependency> pluginDependencyOp = directDependencies.isEmpty() ? Optional.<Dependency>empty()
              : directDependencies.stream().filter(dependency -> dependency.getArtifact().getGroupId().equals(pluginGroupId)
                  && dependency.getArtifact().getArtifactId().equals(pluginArtifactId)).findFirst();
          if (!pluginDependencyOp.isPresent() || !pluginDependencyOp.get().getScope().endsWith(PROVIDED)) {
            throw new IllegalStateException("Plugin '" + pluginCoords
                + "' in order to be resolved has to be declared as provided direct dependency of your Maven project");
          }
          Dependency pluginDependency = pluginDependencyOp.get();
          pluginVersion = pluginDependency.getArtifact().getVersion();
        }

        final DefaultArtifact artifact = new DefaultArtifact(pluginGroupId, pluginArtifactId, "jar", pluginVersion);
        logger.debug("'{}' plugin coordinates resolved to: '{}'", pluginCoords, artifact);
        List<URL> urls = toUrl(localRepositoryService
            .resolveDependencies(
                                 new Dependency(artifact,
                                                COMPILE),
                                 classpathFilter(COMPILE)));

        // TODO: check if exported classes already belong to this plugin...
        pluginUrlClassifications
            .add(new PluginUrlClassification(artifact.toString(), urls, Lists.newArrayList(context.getExportPluginClasses())));
        // TODO generate extension metadata!
      }
    }
    return pluginUrlClassifications;
  }

  private List<URL> buildContainerUrlClassification(LocalRepositoryService localRepositoryService,
                                                    ClassPathClassifierContext context) {
    ArtifactDescriptorResult muleContainerArtifactDescriptorResult =
        localRepositoryService.readArtifactDescriptor(new DefaultArtifact(MULE_STANDALONE_ARTIFACT));

    List<URL> containerUrls = toUrl(localRepositoryService
        .resolveDependencies(new Dependency(muleContainerArtifactDescriptorResult.getArtifact(),
                                            PROVIDED, false, Lists.newArrayList(
                                                                                new Exclusion(ORG_MULE_EXTENSIONS_GROUP_ID,
                                                                                              MULE_EXTENSIONS_ALL_ARTIFACT_ID,
                                                                                              "*", "*"),
                                                                                new Exclusion(ORG_MULE_TESTS_GROUP_ID, "*", "*",
                                                                                              "*"))),
                             new PatternExclusionsDependencyFilter("junit", "org.hamcrest")));
    containerUrls = containerUrls.stream().filter(url -> !url.getFile().endsWith("pom.xml")).collect(toList());
    resolveSnapshotVersionsFromClasspath(containerUrls, context.getClassPathURLs());
    return containerUrls;
  }

  /**
   * Converts the {@link List} of {@link File}s to {@link URL}s
   *
   * @param files {@link File} to get {@link URL}s
   * @return {@link List} of {@link URL}s
   */
  private List<URL> toUrl(Collection<File> files) {
    List<URL> urls = Lists.newArrayList();
    for (File file : files) {
      try {
        urls.add(file.toURI().toURL());
      } catch (MalformedURLException e) {
        throw new IllegalArgumentException("Couldn't get URL", e);
      }
    }
    return urls;
  }

  // http://www.codegur.me/27185052/intellij-uses-snapshots-with-timestamps-instead-of-snapshot-to-build-artifact
  private void resolveSnapshotVersionsFromClasspath(List<URL> resolvedURLs, List<URL> classpathURLs) {
    Map<File, List<URL>> classpathFolders = Maps.newHashMap();
    classpathURLs.forEach(url -> {
      File folder = new File(url.getFile()).getParentFile();
      if (classpathFolders.containsKey(folder)) {
        classpathFolders.get(folder).add(url);
      } else {
        classpathFolders.put(folder, Lists.newArrayList(url));
      }
    });

    // TODO: improve this code! shame on you gfernandes! this is a terrible hack!
    FileFilter snapshotFileFilter = new WildcardFileFilter("*-SNAPSHOT*.*");
    ListIterator<URL> listIterator = resolvedURLs.listIterator();
    while (listIterator.hasNext()) {
      File artifactResolvedFile = new File(listIterator.next().getFile());
      if (snapshotFileFilter.accept(artifactResolvedFile)) {
        File artifactResolvedFileParentFile = artifactResolvedFile.getParentFile();
        if (classpathFolders.containsKey(artifactResolvedFileParentFile)) {
          List<URL> urls = classpathFolders.get(artifactResolvedFileParentFile);
          if (urls.size() == 1) {
            listIterator.set(urls.get(0));
          } else {
            for (URL url : urls) {
              if (artifactResolvedFile.getName().endsWith("-tests.jar")) {
                if (url.getFile().endsWith("-tests.jar")) {
                  listIterator.set(url);
                  break;
                }
              } else {
                if (!url.getFile().endsWith("-tests.jar")) {
                  listIterator.set(url);
                  break;
                }
              }
            }
          }
        } else {
          logger.warn(
                      "'{}' resolved SNAPSHOT version from URL Container dependencies couldn't be matched to a classpath URL",
                      artifactResolvedFile);
          //logger.warn(
          //            "'{}' resolved SNAPSHOT version from URL Container dependencies couldn't be matched to a classpath URL therefore it is going to be ignored",
          //            artifactResolvedFile);
          //listIterator.remove();
        }
      }
    }
  }

}
