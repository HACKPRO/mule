/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.functional.classloading.isolation.classification.aether;

import static org.eclipse.aether.util.artifact.JavaScopes.COMPILE;
import static org.eclipse.aether.util.artifact.JavaScopes.PROVIDED;
import static org.eclipse.aether.util.filter.DependencyFilterUtils.classpathFilter;
import static org.eclipse.aether.util.filter.DependencyFilterUtils.orFilter;
import org.mule.functional.api.classloading.isolation.ArtifactUrlClassification;
import org.mule.functional.api.classloading.isolation.ClassPathClassifier;
import org.mule.functional.api.classloading.isolation.ClassPathClassifierContext;
import org.mule.functional.api.classloading.isolation.PluginUrlClassification;

import com.google.common.collect.Lists;

import java.io.File;
import java.io.FileFilter;
import java.io.FileReader;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.Exclusion;
import org.eclipse.aether.util.artifact.JavaScopes;
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

  protected final Logger logger = LoggerFactory.getLogger(this.getClass());

  @Override
  public ArtifactUrlClassification classify(ClassPathClassifierContext context) {
    LocalRepositoryService localRepositoryService = new LocalRepositoryService(context.getMavenMultiModuleArtifactMapping());

    List<File> containerFiles = resolveContainerDependencies(localRepositoryService);
    List<URL> containerUrls = toURLs(containerFiles);
    resolveSnapshotVersions(containerFiles, containerUrls, context.getClassPathURLs());

    List<PluginUrlClassification> pluginUrlClassifications = buildPluginClassifications(context, localRepositoryService);
    //List<URL> allPluginURLs = pluginUrlClassifications.stream().flatMap(p -> p.getUrls().stream()).collect(Collectors.toList());

    List<URL> applicationUrls = buildApplicationURLs(context, localRepositoryService);

    return new ArtifactUrlClassification(containerUrls, pluginUrlClassifications,
                                         applicationUrls);
  }

  private List<URL> buildApplicationURLs(ClassPathClassifierContext context, LocalRepositoryService localRepositoryService) {
    File pom = new File(context.getRootArtifactClassesFolder().getParentFile().getParentFile(), "/pom.xml");
    Model model = loadMavenModel(pom);

    Artifact currentArtifact =
        new DefaultArtifact(model.getGroupId(), model.getArtifactId(), model.getPackaging(),
                            model.getVersion() != null ? model.getVersion() : model.getParent().getVersion());
    List<Dependency> directDependencies = localRepositoryService
        .getDirectDependencies(currentArtifact);

    // Adding test classes!
    List<File> applicationFiles = Lists.newArrayList(context.getRootArtifactTestClassesFolder());
    directDependencies = directDependencies.stream().filter(dependency -> {
      String scope = dependency.getScope();
      return !dependency.isOptional() && scope.equalsIgnoreCase(JavaScopes.TEST);
    }).collect(Collectors.toList());
    applicationFiles
        .addAll(localRepositoryService.resolveDependencies(directDependencies, orFilter(new PatternExclusionsDependencyFilter(
                                                                                                                              "org.mule",
                                                                                                                              "org.mule.modules*",
                                                                                                                              "org.mule.transports",
                                                                                                                              "org.mule.mvel",
                                                                                                                              "org.mule.common",
                                                                                                                              "org.mule.extensions",
                                                                                                                              "junit",
                                                                                                                              "org.hamcrest"),
                                                                                        new PatternInclusionsDependencyFilter("org.mule.modules:mule-module-extensions-support:jar:tests:*"))));

    return toURLs(applicationFiles);
  }

  private Model loadMavenModel(File pomFile) {
    MavenXpp3Reader mavenReader = new MavenXpp3Reader();

    if (pomFile != null && pomFile.exists()) {
      FileReader reader = null;

      try {
        reader = new FileReader(pomFile);
        Model model = mavenReader.read(reader);

        Properties properties = model.getProperties();
        properties.setProperty("basedir", pomFile.getParent());
        Parent parent = model.getParent();

        if (parent != null) {
          File parentPom = new File(pomFile.getParent(), parent.getRelativePath());
          Model parentProj = loadMavenModel(parentPom);

          if (parentProj == null) {
            throw new RuntimeException("Unable to load parent project at: " + parentPom.getAbsolutePath());
          }

          properties.putAll(parentProj.getProperties());
        }

        return model;
      } catch (Exception e) {
        throw new RuntimeException("Couldn't get Maven Artifact from pom: " + pomFile);
      } finally {
        try {
          reader.close();
        } catch (IOException e) {
          // Nothing to do..
        }
      }
    }
    throw new IllegalArgumentException("pom file doesn't exits for path: " + pomFile);
  }

  private List<PluginUrlClassification> buildPluginClassifications(ClassPathClassifierContext context,
                                                                   LocalRepositoryService localRepositoryService) {
    List<PluginUrlClassification> pluginUrlClassifications = Lists.newArrayList();
    if (context.getPluginCoordinates() != null) {
      for (String pluginCoords : context.getPluginCoordinates()) {
        List<URL> urls = toURLs(localRepositoryService
            .resolveDependencies(
                                 new Dependency(new DefaultArtifact(pluginCoords),
                                                COMPILE),
                                 classpathFilter(COMPILE)));

        pluginUrlClassifications
            .add(new PluginUrlClassification(pluginCoords, urls, Lists.newArrayList(context.getExportClasses())));
        // TODO generate extension metadata!
      }
    }
    return pluginUrlClassifications;
  }

  private List<File> resolveContainerDependencies(LocalRepositoryService localRepositoryService) {
    return localRepositoryService
        .resolveDependencies(new Dependency(new DefaultArtifact(MULE_STANDALONE_ARTIFACT),
                                            PROVIDED, false, Lists.newArrayList(
                                                                                new Exclusion(ORG_MULE_EXTENSIONS_GROUP_ID,
                                                                                              MULE_EXTENSIONS_ALL_ARTIFACT_ID,
                                                                                              "*", "pom"),
                                                                                new Exclusion(ORG_MULE_TESTS_GROUP_ID, "*", "*",
                                                                                              "*"))),
                             new PatternExclusionsDependencyFilter("junit", "org.hamcrest"));
  }

  // http://www.codegur.me/27185052/intellij-uses-snapshots-with-timestamps-instead-of-snapshot-to-build-artifact
  private void resolveSnapshotVersions(List<File> containerFiles, List<URL> containerUrls, List<URL> classpath) {
    try {
      FileFilter snapshotFileFilter = new WildcardFileFilter("*-SNAPSHOT*.*");
      for (File artifactFile : containerFiles) {
        if (snapshotFileFilter.accept(artifactFile)) {
          for (URL appURL : classpath) {
            if (artifactFile.getParentFile().equals(new File(appURL.getFile()).getParentFile())) {
              containerUrls.set(containerUrls.indexOf(artifactFile.toURI().toURL()), appURL);
              break;
            }
          }
        }
      }
    } catch (MalformedURLException e) {
      throw new RuntimeException("Error while getting URL", e);
    }
  }

  private List<URL> toURLs(Collection<File> files) {
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
}
