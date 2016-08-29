/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.functional.classloading.isolation.classification.aether;

import static org.mule.functional.classloading.isolation.classification.aether.DefaultWorkspaceReader.findClassPathURL;
import org.mule.functional.api.classloading.isolation.WorkspaceLocationResolver;

import java.io.File;
import java.net.URL;
import java.util.List;

import org.eclipse.aether.collection.DependencyCollectionContext;
import org.eclipse.aether.collection.DependencySelector;
import org.eclipse.aether.graph.Dependency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides what dependencies to include in the dependency graph taking into account the class path and workspace references.
 *
 * @since 4.0
 */
public class WorkspaceDependencySelector implements DependencySelector {

  public static final String POM = "pom";
  protected final Logger logger = LoggerFactory.getLogger(this.getClass());

  private List<URL> classPath;
  private WorkspaceLocationResolver workspaceLocationResolver;
  private int hashCode;

  public WorkspaceDependencySelector(List<URL> classPath, WorkspaceLocationResolver workspaceLocationResolver) {
    this.classPath = classPath;
    this.workspaceLocationResolver = workspaceLocationResolver;
  }

  /**
   * Decides whether the specified dependency should be included in the dependency graph.
   * <p/>
   * If the dependency is not part of the workspace or it has a {@value #POM} extension it will be selected to be included in the
   * dependency graph. This is the case for dependencies that need to be resolved from the local Maven repository.
   * <p/>
   * When the dependency is part of the workspace it is going to be selected only if there is a matching {@link URL} in the class
   * path.
   *
   * @param dependency The dependency to check, must not be {@code null}.
   * @return {@code false} if the dependency should be excluded from the children of the current node, {@code true} otherwise.
   */
  @Override
  public boolean selectDependency(Dependency dependency) {
    File workspaceRef = workspaceLocationResolver.resolvePath(dependency.getArtifact().getArtifactId());
    if (workspaceRef == null || dependency.getArtifact().getExtension().equals(POM)) {
      return true;
    }
    boolean select = findClassPathURL(dependency.getArtifact(), workspaceRef, classPath) != null;
    if (!select) {
      if (logger.isDebugEnabled()) {
        logger
            .warn("'{}' dependency found in dependency graph with a workspace reference at '{}' but not found in class path, therefore it will be ignored",
                  dependency.getArtifact(), workspaceRef.getAbsolutePath());
      }
    }
    return select;
  }

  /**
   * Nothing changes so the same instance has to be returned here. See {@link DependencySelector#deriveChildSelector(DependencyCollectionContext)}
   *
   * @param context The dependency collection context, must not be {@code null}.
   * @return The dependency selector for the target node or {@code null} if dependencies should be unconditionally
   *         included in the sub graph.
   */
  @Override
  public DependencySelector deriveChildSelector(DependencyCollectionContext context) {
    return this;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    } else if (null == obj || !getClass().equals(obj.getClass())) {
      return false;
    }

    WorkspaceDependencySelector that = (WorkspaceDependencySelector) obj;
    return classPath.equals(that.classPath)
        && workspaceLocationResolver.equals(that.workspaceLocationResolver);
  }


  @Override
  public int hashCode() {
    if (hashCode == 0) {
      int hash = getClass().hashCode();
      hash = hash * 31 + classPath.hashCode();
      hash = hash * 31 + workspaceLocationResolver.hashCode();

      hashCode = hash;
    }
    return hashCode;
  }

}
