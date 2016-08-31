/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.functional.junit4.runners;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;
import static org.mule.functional.util.AnnotationUtils.getAnnotationAttributeFrom;
import static org.mule.functional.util.AnnotationUtils.getAnnotationAttributeFromHierarchy;
import static org.mule.runtime.core.util.ClassUtils.withContextClassLoader;
import org.mule.functional.api.classloading.isolation.ArtifactClassLoaderHolder;
import org.mule.functional.api.classloading.isolation.ArtifactIsolatedClassLoaderBuilder;
import org.mule.functional.api.classloading.isolation.ClassPathClassifier;
import org.mule.functional.api.classloading.isolation.ClassPathUrlProvider;
import org.mule.functional.classloading.isolation.classification.AetherClassPathClassifier;
import org.mule.functional.classloading.isolation.maven.AutoDiscoverWorkspaceLocationResolver;
import org.mule.runtime.module.artifact.classloader.ArtifactClassLoader;

import java.io.File;
import java.lang.annotation.Annotation;
import java.net.URL;
import java.util.Arrays;
import java.util.List;

import org.junit.internal.builders.AnnotatedBuilder;
import org.junit.runner.Description;
import org.junit.runner.Runner;
import org.junit.runner.manipulation.Filter;
import org.junit.runner.manipulation.Filterable;
import org.junit.runner.manipulation.NoTestsRemainException;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.RunnerBuilder;
import org.junit.runners.model.TestClass;

/**
 * A {@link org.junit.runner.Runner} that mimics the class loading model used in a Mule Standalone distribution. In order to
 * detect early issues related to isolation when building plugins these runner allows you to run your functional test cases using
 * an isolated class loader.
 * <p/>
 * {@link org.mule.functional.junit4.ArtifactFunctionalTestCase} should be extended in order to use this runner, it has already
 * annotated the runner and also has the logic to register {@link org.mule.runtime.extension.api.annotation.Extension} to a
 * {@link org.mule.runtime.core.api.MuleContext}.
 * <p/>
 * See {@link RunnerDelegateTo} for those scenarios where another JUnit runner needs to be used but still the test has to be
 * executed within an isolated class loading model. {@link ArtifactClassLoaderRunnerConfig} allows to define the plugins in order
 * to create the class loaders for them, for each one a plugin class loader would be created. {@link PluginClassLoadersAware}
 * allows the test to be injected with the list of {@link ClassLoader}s that were created for each plugin, mostly used in
 * {@link org.mule.functional.junit4.ArtifactFunctionalTestCase} in order to enable plugins into a
 * {@link org.mule.runtime.core.api.MuleContext}.
 * <p/>
 * The class loading model is built by doing a classification of the class path {@link URL}s loaded by IDEs or Maven.
 * {@link ClassPathClassifier} defines the strategy of classification to be used in order to define the
 * {@link ArtifactClassLoaderHolder}, classification would define three levels of {@link URL}s that would be used for creating a
 * container {@link ArtifactClassLoader}, list of plugins {@link ArtifactClassLoader} and an application
 * {@link ArtifactClassLoader}.
 * <p/>
 * The classification bases its logic by resolving Maven dependency graphs using Eclipse Aether. See
 * {@link AetherClassPathClassifier} for more details about this.
 * <p/>
 * Only one instance of the {@link ClassLoader} is created and used to run all the tests that are marked to run with this
 * {@link Runner} due to creating the {@link ClassLoader} requires time and has impact when running tests.
 * <p/>
 * A best practice is to a base abstract class for your module tests that extends
 * {@link org.mule.functional.junit4.ArtifactFunctionalTestCase} and defines if needed anything related to the configuration with
 * this annotation that will be applied to all the tests that are being executed for the same VM.
 *
 * @since 4.0
 */
public class ArtifactClassLoaderRunner extends Runner implements Filterable {

  private static ArtifactClassLoaderHolder artifactClassLoaderHolder;
  private static boolean pluginClassLoadersInjected = false;
  private final Runner delegate;

  /**
   * Creates a Runner to run {@code klass}
   *
   * @param clazz
   * @param builder
   * @throws Throwable if there was an error while initializing the runner.
   */
  public ArtifactClassLoaderRunner(Class<?> clazz, RunnerBuilder builder) throws Throwable {
    if (artifactClassLoaderHolder == null) {
      artifactClassLoaderHolder = createClassLoaderTestRunner(clazz);
    }

    final Class<?> isolatedTestClass = getTestClass(clazz);

    final Class<? extends Annotation> runnerDelegateToClass = (Class<? extends Annotation>) artifactClassLoaderHolder
        .loadClassWithApplicationClassLoader(RunnerDelegateTo.class.getName());

    final AnnotatedBuilder annotatedBuilder = new AnnotatedBuilder(builder);
    delegate = annotatedBuilder.buildRunner(getAnnotationAttributeFrom(isolatedTestClass, runnerDelegateToClass, "value"),
                                            isolatedTestClass);

    if (!pluginClassLoadersInjected) {
      injectPluginsClassLoaders(artifactClassLoaderHolder, isolatedTestClass);
      pluginClassLoadersInjected = true;
    }
  }

  /**
   * Creates the {@link ArtifactClassLoaderHolder} with the isolated class loaders.
   *
   * @param klass the test class being executed
   * @return creates a {@link ArtifactClassLoaderHolder} that would be used to run the test. This way the test will be isolated
   *         and it will behave similar as an application running in a Mule standalone container.
   */
  private static synchronized ArtifactClassLoaderHolder createClassLoaderTestRunner(Class<?> klass) {
    final File targetTestClassesFolder = new File(klass.getProtectionDomain().getCodeSource().getLocation().getPath());

    ArtifactIsolatedClassLoaderBuilder builder = new ArtifactIsolatedClassLoaderBuilder();
    final File rootArtifactClassesFolder = new File(targetTestClassesFolder.getParentFile(), "classes");
    builder.setRootArtifactClassesFolder(rootArtifactClassesFolder);
    builder.setRootArtifactTestClassesFolder(targetTestClassesFolder);

    List<String[]> providedExclusionsList =
        getAnnotationAttributeFromHierarchy(klass, ArtifactClassLoaderRunnerConfig.class,
                                            "providedExclusions");
    builder.setProvidedExclusions(providedExclusionsList.stream()
        .flatMap(Arrays::stream).collect(toList()));

    List<String[]> providedInclusionsList =
        getAnnotationAttributeFromHierarchy(klass, ArtifactClassLoaderRunnerConfig.class,
                                            "providedInclusions");
    builder.setProvidedInclusions(providedInclusionsList.stream()
        .flatMap(Arrays::stream).collect(toList()));

    List<String[]> applicationArtifactExclusionsDependencyFilterList =
        getAnnotationAttributeFromHierarchy(klass, ArtifactClassLoaderRunnerConfig.class,
                                            "testExclusions");
    builder.setTestExclusions(applicationArtifactExclusionsDependencyFilterList.stream()
        .flatMap(Arrays::stream).collect(toList()));

    List<Class[]> exportPluginClassesList =
        getAnnotationAttributeFromHierarchy(klass, ArtifactClassLoaderRunnerConfig.class, "exportPluginClasses");
    builder.setExportPluginClasses(exportPluginClassesList.stream().flatMap(Arrays::stream).collect(toSet()));

    List<String[]> pluginCoordinatesFromHierarchy =
        getAnnotationAttributeFromHierarchy(klass, ArtifactClassLoaderRunnerConfig.class, "plugins");
    builder.setPluginCoordinates(pluginCoordinatesFromHierarchy.stream().flatMap(Arrays::stream).collect(toSet()).stream()
        .collect(toList()));

    final ClassPathUrlProvider classPathUrlProvider = new ClassPathUrlProvider();
    builder.setClassPathUrlProvider(classPathUrlProvider);
    builder.setClassPathClassifier(new AetherClassPathClassifier());
    builder.setWorkspaceLocationResolver(new AutoDiscoverWorkspaceLocationResolver(classPathUrlProvider.getURLs(),
                                                                                   rootArtifactClassesFolder));

    return builder.build();
  }

  /**
   * Invokes the method to inject the plugin class loaders as the test is annotated with {@link PluginClassLoadersAware}.
   *
   * @param artifactClassLoaderHolder the result {@link ArtifactClassLoader}s defined for container, plugins and application
   * @param isolatedTestClass the test {@link Class} loaded with the isolated {@link ClassLoader}
   * @throws IllegalStateException if the test doesn't have an annotated method to inject plugin class loaders or if it has more
   *         than one method annotated.
   * @throws Throwable if an error ocurrs while setting the list of {@link ArtifactClassLoader}s for plugins.
   */
  private static void injectPluginsClassLoaders(ArtifactClassLoaderHolder artifactClassLoaderHolder, Class<?> isolatedTestClass)
      throws Throwable {
    TestClass testClass = new TestClass(isolatedTestClass);
    Class<? extends Annotation> artifactContextAwareAnn = (Class<? extends Annotation>) artifactClassLoaderHolder
        .loadClassWithApplicationClassLoader(PluginClassLoadersAware.class.getName());
    List<FrameworkMethod> contextAwareMethods = testClass.getAnnotatedMethods(artifactContextAwareAnn);
    if (contextAwareMethods.size() != 1) {
      throw new IllegalStateException("Isolation tests need to have one method marked with annotation "
          + PluginClassLoadersAware.class.getName());
    }
    for (FrameworkMethod method : contextAwareMethods) {
      if (!method.isStatic() || method.isPublic()) {
        throw new IllegalStateException("Method marked with annotation " + PluginClassLoadersAware.class.getName()
            + " should be private static and it should receive a parameter of type List<" + ArtifactClassLoader.class + ">");
      }
      method.getMethod().setAccessible(true);
      try {
        method.invokeExplosively(null, artifactClassLoaderHolder.getPluginsClassLoaders());
      } catch (IllegalArgumentException e) {
        throw new IllegalStateException("Method marked with annotation " + PluginClassLoadersAware.class.getName()
            + " should receive a parameter of type List<" + ArtifactClassLoader.class + ">");
      } finally {
        method.getMethod().setAccessible(false);
      }
    }
  }

  private Class<?> getTestClass(Class<?> clazz) throws InitializationError {
    try {
      return artifactClassLoaderHolder.loadClassWithApplicationClassLoader(clazz.getName());
    } catch (Exception e) {
      throw new InitializationError(e);
    }
  }

  /**
   * @return delegates to the internal runner to get the description needed by JUnit.
   */
  @Override
  public Description getDescription() {
    return delegate.getDescription();
  }

  /**
   * When the test is about to be executed the ThreadContextClassLoader is changed to use the application class loader that was
   * created so the execution of the test will be done using an isolated class loader that mimics the standalone container.
   *
   * @param notifier the {@link RunNotifier} from JUnit that will be notified about the results of the test methods invoked.
   */
  @Override
  public void run(RunNotifier notifier) {
    withContextClassLoader(artifactClassLoaderHolder.getApplicationClassLoader().getClassLoader(), () -> delegate.run(notifier));
  }

  /**
   * Delegates to the inner runner to filter.
   *
   * @param filter the {@link Filter} from JUnit to select a single test.
   * @throws NoTestsRemainException
   */
  @Override
  public void filter(Filter filter) throws NoTestsRemainException {
    if (delegate instanceof Filterable) {
      ((Filterable) delegate).filter(filter);
    }
  }
}
