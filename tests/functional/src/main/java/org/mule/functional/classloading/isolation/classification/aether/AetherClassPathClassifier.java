/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.functional.classloading.isolation.classification.aether;

import static java.util.stream.Collectors.toList;
import static org.eclipse.aether.util.artifact.JavaScopes.COMPILE;
import static org.eclipse.aether.util.artifact.JavaScopes.PROVIDED;
import static org.eclipse.aether.util.filter.DependencyFilterUtils.classpathFilter;
import org.mule.functional.api.classloading.isolation.ArtifactUrlClassification;
import org.mule.functional.api.classloading.isolation.ClassPathClassifier;
import org.mule.functional.api.classloading.isolation.ClassPathClassifierContext;
import org.mule.functional.api.classloading.isolation.PluginUrlClassification;

import com.google.common.collect.Lists;

import java.io.File;
import java.io.FileFilter;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.Exclusion;
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

  public static void main(String[] args)
      throws Exception {
    new AetherClassPathClassifier().classify(null);
  }

  @Override
  public ArtifactUrlClassification classify(ClassPathClassifierContext context) {
    LocalRepositoryService localRepositoryService = new LocalRepositoryService(context.getMavenMultiModuleArtifactMapping());

    List<File> containerFiles = localRepositoryService
        .resolveDependencies(new Dependency(new DefaultArtifact(MULE_STANDALONE_ARTIFACT),
                                            PROVIDED, false, Lists.newArrayList(
                                 new Exclusion(ORG_MULE_EXTENSIONS_GROUP_ID, MULE_EXTENSIONS_ALL_ARTIFACT_ID, "*", "pom"),
                                 new Exclusion(ORG_MULE_TESTS_GROUP_ID, "*", "*", "*"))
                             ),
                             new PatternExclusionsDependencyFilter(//TODO seems to be an issue with javax packages and com.sun implementation
                                                                   "com.sun.xml.bind:jaxb-impl:*"));
    List<URL> containerUrls = toURLs(containerFiles);

    List<URL> fileExtensionURLs = toURLs(localRepositoryService
        .resolveDependencies(
                             new Dependency(new DefaultArtifact("org.mule.modules:mule-module-file:jar:4.0-SNAPSHOT"),
                                            COMPILE),
                             classpathFilter(COMPILE)));

    List<URL> applicationUrls = context.getClassPathURLs().stream().filter(
                                                                           url -> !url.getPath().startsWith("/Library/Java/")
                                                                               && !url.getPath()
                                                                                   .startsWith("/Applications/")
                                                                               && !containerUrls.contains(url)
                                                                               && !fileExtensionURLs.contains(url))
        .collect(
                 toList());

    //TODO: find a better way to do this
    List<URL> appSnapshotsToBeRemoved =
        applicationUrls.stream().filter(url -> {
          if (url.getPath().contains("SNAPSHOT/")) {
            File snapshotTimestampFile = new File(url.getPath());
            File[] snapshotFiles =
                snapshotTimestampFile.getParentFile().listFiles((FileFilter) new WildcardFileFilter("*-SNAPSHOT*.*"));
            for (int i = 0; i < snapshotFiles.length; i++) {
              if (containerFiles.contains(snapshotFiles[i])) {
                return true;
              }
            }
          }
          return false;
        }).collect(Collectors.toList());
    applicationUrls.removeAll(appSnapshotsToBeRemoved);

    return new ArtifactUrlClassification(containerUrls, Lists.newArrayList(
                                                                           new PluginUrlClassification("org.mule.extension.file.internal.FileConnector",
                                                                                                       fileExtensionURLs,
                                                                                                       Lists.newArrayList())),
                                         applicationUrls);
  }

  private List<URL> toURLs(List<File> files) {
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
