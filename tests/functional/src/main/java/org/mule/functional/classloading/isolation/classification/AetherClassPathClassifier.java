/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.functional.classloading.isolation.classification;

import static java.util.stream.Collectors.toList;
import static org.apache.commons.lang3.StringUtils.isBlank;
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
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.util.filter.PatternExclusionsDependencyFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TODO
 */
public class AetherClassPathClassifier implements ClassPathClassifier {

  public static final String ALL_TESTS_JAR_ARTIFACT_COORDS = "*:*:jar:tests:*";
  public static final String GENERATED_TEST_RESOURCES = "generate-test-resources";

  public static final String POM = "pom";
  public static final String POM_XML = POM + ".xml";
  public static final String MAVEN_COORDINATES_SEPARATOR = ":";
  public static final String JAR_EXTENSION = "jar";

  protected final Logger logger = LoggerFactory.getLogger(this.getClass());

  @Override
  public ArtifactUrlClassification classify(ClassPathClassifierContext context) {
    LocalRepositoryService localRepositoryService =
        new LocalRepositoryService(context.getClassPathURLs(), context.getWorkspaceLocationResolver());

    Artifact rootArtifact = getRootArtifact(context);
    if (logger.isDebugEnabled()) {
      logger.debug("Building class loaders for rootArtifact: {}", rootArtifact);
    }
    List<Dependency> directDependencies = localRepositoryService
        .getDirectDependencies(rootArtifact);

    List<URL> containerUrls = buildContainerUrlClassification(localRepositoryService, context, rootArtifact);
    List<PluginUrlClassification> pluginUrlClassifications =
        buildPluginUrlClassifications(context, rootArtifact, directDependencies, localRepositoryService);
    List<URL> applicationUrls = buildApplicationUrlClassification(context, rootArtifact, directDependencies,
                                                                  localRepositoryService, pluginUrlClassifications);
    List<URL> bootLauncherUrls = getBootLauncherURLs(context);

    return new ArtifactUrlClassification(bootLauncherUrls, containerUrls, pluginUrlClassifications,
                                         applicationUrls);
  }

  /**
   * Gets the Maven artifact for located at {@link ClassPathClassifierContext#getRootArtifactClassesFolder()}
   *
   * @param context {@link ClassPathClassifierContext} for classification process
   * @return {@link Artifact} that represents the rootArtifact
   */
  private Artifact getRootArtifact(ClassPathClassifierContext context) {
    File pomFile = new File(context.getRootArtifactClassesFolder().getParentFile().getParentFile(), POM_XML);
    if (logger.isDebugEnabled()) {
      logger.debug("Reading rootArtifact from pom file: {}", pomFile);
    }
    Model model = MavenModelFactory.createMavenProject(pomFile);

    return new DefaultArtifact(model.getGroupId() != null ? model.getGroupId() : model.getParent().getGroupId(),
                               model.getArtifactId(), model.getPackaging(),
                               model.getVersion() != null ? model.getVersion() : model.getParent().getVersion());
  }

  private List<URL> buildContainerUrlClassification(LocalRepositoryService localRepositoryService,
                                                    ClassPathClassifierContext context, Artifact rootArtifact) {
    String muleContainerCoordinates = context.getMuleContainerCoordinates();
    if (logger.isDebugEnabled()) {
      logger.debug("Using Mule container maven coordinates: '{}'", muleContainerCoordinates);
    }
    String muleContainerVersion = context.getMuleContainerVersion();
    if (isBlank(muleContainerVersion)) {
      if (logger.isDebugEnabled()) {
        logger.debug("No version defined for mule container, using rootArtifact version");
      }
      muleContainerVersion = rootArtifact.getVersion();
    }

    if (logger.isDebugEnabled()) {
      logger.debug("Mule version set: '{}'", muleContainerVersion);
    }
    Artifact muleArtifactDefinition = new DefaultArtifact(muleContainerCoordinates + MAVEN_COORDINATES_SEPARATOR + POM
        + MAVEN_COORDINATES_SEPARATOR + muleContainerVersion);
    if (logger.isDebugEnabled()) {
      logger.debug("Mule container artifact defined to: '{}'", muleArtifactDefinition);
    }

    ArtifactResult muleContainerArtifactResult =
        localRepositoryService.resolveArtifact(muleArtifactDefinition);

    List<Exclusion> exclusions = Lists.newArrayList();
    context.getMuleContainerExclusions().forEach(exclusionCoordinates -> {
      Artifact exclusionArtifact = new DefaultArtifact(exclusionCoordinates);
      Exclusion exclusion = new Exclusion(exclusionArtifact.getGroupId(), exclusionArtifact.getArtifactId(),
                                          exclusionArtifact.getExtension(), exclusionArtifact.getVersion());
      if (logger.isDebugEnabled()) {
        logger.debug("Exclusion defined for Mule container: '{}'", exclusion);
      }
      exclusions.add(exclusion);
    });

    if (!context.getExcludedArtifacts().isEmpty()) {
      if (logger.isDebugEnabled()) {
        logger.debug("Filtering artifacts using coordinates: {}", context.getExcludedArtifacts());
      }
    }
    List<URL> containerUrls = toUrl(localRepositoryService
        .resolveDependencies(new Dependency(muleContainerArtifactResult.getArtifact(),
                                            PROVIDED, false, exclusions),
                             new PatternExclusionsDependencyFilter(context.getExcludedArtifacts())));

    containerUrls = containerUrls.stream().filter(url -> !url.getFile().endsWith(POM_XML)).collect(toList());
    resolveSnapshotVersionsFromClasspath(containerUrls, context.getClassPathURLs());
    return containerUrls;
  }

  private List<PluginUrlClassification> buildPluginUrlClassifications(ClassPathClassifierContext context,
                                                                      Artifact rootArtifact,
                                                                      List<Dependency> directDependencies,
                                                                      LocalRepositoryService localRepositoryService) {
    List<PluginUrlClassification> pluginUrlClassifications = Lists.newArrayList();
    if (context.getPluginCoordinates() != null) {
      for (String pluginCoords : context.getPluginCoordinates()) {
        if (logger.isDebugEnabled()) {
          logger.debug("Building plugin classification for coordinates: '{}'", pluginCoords);
        }

        final String[] pluginSplitCoords = pluginCoords.split(MAVEN_COORDINATES_SEPARATOR);
        String pluginGroupId = pluginSplitCoords[0];
        String pluginArtifactId = pluginSplitCoords[1];
        String pluginVersion;

        if (rootArtifact.getGroupId().equals(pluginGroupId) && rootArtifact.getArtifactId().equals(pluginArtifactId)) {
          if (logger.isDebugEnabled()) {
            logger.debug("'{}' declared as plugin, resolving version from pom file", rootArtifact);
          }
          pluginVersion = rootArtifact.getVersion();
        } else {
          if (logger.isDebugEnabled()) {
            logger.debug("Resolving version for '{}' from direct dependencies", pluginCoords);
          }
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

        final DefaultArtifact artifact = new DefaultArtifact(pluginGroupId, pluginArtifactId, JAR_EXTENSION, pluginVersion);
        if (logger.isDebugEnabled()) {
          logger.debug("'{}' plugin coordinates resolved to: '{}'", pluginCoords, artifact);
        }
        List<URL> urls = toUrl(localRepositoryService
            .resolveDependencies(
                                 new Dependency(artifact,
                                                COMPILE),
                                 classpathFilter(COMPILE)));

        // TODO (gfernandes): How could I check if exported classes belong to this plugin?
        pluginUrlClassifications
            .add(new PluginUrlClassification(artifact.toString(), urls, Lists.newArrayList(context.getExportPluginClasses())));
      }
    }
    return pluginUrlClassifications;
  }

  private List<URL> buildApplicationUrlClassification(ClassPathClassifierContext context,
                                                      Artifact rootArtifact,
                                                      List<Dependency> directDependencies,
                                                      LocalRepositoryService localRepositoryService,
                                                      List<PluginUrlClassification> pluginUrlClassifications) {
    if (logger.isDebugEnabled()) {
      logger.debug("Building application classification");
    }
    List<File> applicationFiles = Lists.newArrayList(context.getRootArtifactTestClassesFolder());

    if (logger.isDebugEnabled()) {
      logger.debug("Setting filter for dependency graph to include: '{}'", ALL_TESTS_JAR_ARTIFACT_COORDS);
    }
    DependencyFilter dependencyFilter = new PatternInclusionsDependencyFilter(
                                                                              ALL_TESTS_JAR_ARTIFACT_COORDS);

    boolean isRootArtifactPlugin = !pluginUrlClassifications.isEmpty()
        && pluginUrlClassifications.stream().filter(p -> {
          Artifact plugin = new DefaultArtifact(p.getName());
          return plugin.getGroupId().equals(rootArtifact.getGroupId())
              && plugin.getArtifactId().equals(rootArtifact.getArtifactId());
        }).findFirst().isPresent();

    List<String> exclusionsPatterns = Lists.newArrayList();

    if (!isRootArtifactPlugin && context.getRootArtifactClassesFolder().exists()) {
      if (logger.isDebugEnabled()) {
        logger.debug("RootArtifact is not a plugin so '{}' is added to application classification",
                     context.getRootArtifactClassesFolder());
      }
      applicationFiles.add(context.getRootArtifactClassesFolder());
    } else {
      if (logger.isDebugEnabled()) {
        logger.debug("RootArtifact is a plugin or it doesn't have a target/classes folder (it is the case of a test artifact)");
      }
      exclusionsPatterns.add(rootArtifact.getGroupId() + MAVEN_COORDINATES_SEPARATOR
          + rootArtifact.getArtifactId() + MAVEN_COORDINATES_SEPARATOR +
          JAR_EXTENSION + MAVEN_COORDINATES_SEPARATOR + rootArtifact.getVersion());
    }

    directDependencies = directDependencies.stream()
        .map(toTransform -> {
          if (toTransform.getScope().equals(TEST)) {
            return new Dependency(toTransform.getArtifact(), COMPILE);
          }
          if (isRootArtifactPlugin && toTransform.getScope().equals(COMPILE)) {
            return new Dependency(toTransform.getArtifact(), PROVIDED);
          }
          return toTransform;
        }).collect(toList());

    if (logger.isDebugEnabled()) {
      logger.debug("OR exclude: {}", context.getExcludedArtifacts());
    }
    exclusionsPatterns.addAll(context.getExcludedArtifacts());

    if (!context.getApplicationArtifactExclusionsCoordinates().isEmpty()) {
      if (logger.isDebugEnabled()) {
        logger.debug("OR exclude application specific artifacts: {}", context.getApplicationArtifactExclusionsCoordinates());
      }
      exclusionsPatterns.addAll(context.getApplicationArtifactExclusionsCoordinates());
    }

    if (logger.isDebugEnabled()) {
      logger.debug("Resolving dependency graph for '{}' scope direct dependencies: {}", TEST, directDependencies);
    }
    applicationFiles
        .addAll(localRepositoryService.resolveDependencies(new Dependency(rootArtifact, TEST), directDependencies,
                                                           orFilter(dependencyFilter,
                                                                    new PatternExclusionsDependencyFilter(exclusionsPatterns))));

    return toUrl(applicationFiles);
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
        }
      }
    }
  }

}
