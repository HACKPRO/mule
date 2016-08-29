/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.functional.api.classloading.isolation;

import static java.io.File.separator;
import static java.util.Arrays.stream;
import static org.mule.runtime.core.util.ClassUtils.withContextClassLoader;
import static org.mule.runtime.core.util.Preconditions.checkArgument;
import static org.mule.runtime.module.extension.internal.ExtensionProperties.EXTENSION_MANIFEST_FILE_NAME;
import static org.springframework.util.ReflectionUtils.findField;
import static org.springframework.util.ReflectionUtils.findMethod;
import org.mule.functional.junit4.infrastructure.ExtensionsTestInfrastructureDiscoverer;
import org.mule.runtime.core.api.MuleContext;
import org.mule.runtime.core.api.lifecycle.InitialisationException;
import org.mule.runtime.core.config.builders.AbstractConfigurationBuilder;
import org.mule.runtime.extension.api.annotation.Extension;
import org.mule.runtime.extension.api.manifest.ExtensionManifest;
import org.mule.runtime.module.artifact.classloader.ArtifactClassLoader;
import org.mule.runtime.module.extension.internal.introspection.version.StaticVersionResolver;
import org.mule.runtime.module.extension.internal.manager.DefaultExtensionManagerAdapterFactory;
import org.mule.runtime.module.extension.internal.manager.ExtensionManagerAdapter;
import org.mule.runtime.module.extension.internal.manager.ExtensionManagerAdapterFactory;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import sun.misc.URLClassPath;

/**
 * A {@link org.mule.runtime.core.api.config.ConfigurationBuilder} that creates an
 * {@link org.mule.runtime.extension.api.ExtensionManager}. It reads the extension manifest file using the extension class loader
 * that loads the extension annotated class and register the extension to the manager.
 *
 * @since 4.0
 */
public class IsolatedClassLoaderExtensionsManagerConfigurationBuilder extends AbstractConfigurationBuilder {

  private static final String GENERATED_TEST_SOURCES = "generated-test-sources";
  private static Logger LOGGER = LoggerFactory.getLogger(IsolatedClassLoaderExtensionsManagerConfigurationBuilder.class);

  private final File targetFolder;
  private final ExtensionManagerAdapterFactory extensionManagerAdapterFactory;
  private final List<ArtifactClassLoader> pluginsClassLoaders;

  /**
   * Creates an instance of the builder with the list of plugin class loaders. If an {@link ArtifactClassLoader} has a extension
   * descriptor it will be registered as an extension if not it is assumed that it is not an extension plugin. The extension will
   * be loaded and registered with its corresponding class loader in order to get access to the isolated {@link ClassLoader}
   * defined for the extension.
   *
   * @param targetFolder {@link File} to target folder
   * @param pluginsClassLoaders the list of {@link ArtifactClassLoader} created for each plugin found in the dependencies (either
   *        plugin or extension plugin).
   */
  public IsolatedClassLoaderExtensionsManagerConfigurationBuilder(final File targetFolder,
                                                                  final List<ArtifactClassLoader> pluginsClassLoaders) {
    this.targetFolder = targetFolder;
    this.extensionManagerAdapterFactory = new DefaultExtensionManagerAdapterFactory();
    this.pluginsClassLoaders = pluginsClassLoaders;
  }

  /**
   * Goes through the list of plugins {@link ArtifactClassLoader}s to check if they have a class annotated with {@link Extension}.
   * If they do, it generates the extension metadata for all the extensions first and then go over the plugin class loaders once
   * again to check which one of them have an extension descriptor and if they do it will parse it and register the extension into
   * the {@link org.mule.runtime.extension.api.ExtensionManager}
   * <p/>
   * It has to use reflection to access these classes due to the current execution of this method would be with the applciation
   * {@link ArtifactClassLoader} and the list of plugin {@link ArtifactClassLoader} was instantiated with the Launcher
   * {@link ClassLoader} so casting won't work here.
   *
   * @param muleContext The current {@link org.mule.runtime.core.api.MuleContext}
   * @throws Exception if an error occurs while registering an extension of calling methods using reflection.
   */
  @Override
  protected void doConfigure(final MuleContext muleContext) throws Exception {
    final ExtensionManagerAdapter extensionManager = createExtensionManager(muleContext);
    final ExtensionsTestInfrastructureDiscoverer extensionsInfrastructure =
        new ExtensionsTestInfrastructureDiscoverer(extensionManager);
    generateDslResources(extensionsInfrastructure, pluginsClassLoaders);
    registerExtensions(extensionManager);
  }

  /**
   * Scans the class loader to look for {@link Class}es annotated with {@link Extension} and generates its extension manifest
   * metadata, it also adds the {@code META-INF} folder where metadata was generated to the class loader using reflection.
   *
   * @param extensionsInfrastructure {@link ExtensionsTestInfrastructureDiscoverer} utility to generate the metadata
   * @param pluginsClassLoaders {@link ArtifactClassLoader} to discover for extensions
   * @throws Exception if an error happens while discovering or generating metadata
   */
  private void generateDslResources(ExtensionsTestInfrastructureDiscoverer extensionsInfrastructure,
                                    List<ArtifactClassLoader> pluginsClassLoaders)
      throws Exception {
    File baseResourcesFolder = getGeneratedResourcesBase();

    for (Object pluginClassLoader : pluginsClassLoaders) {
      String artifactName = getArtifactName(pluginClassLoader);
      ClassLoader classLoader = getClassLoader(pluginClassLoader);

      File generatedResourcesDirectory =
          new File(baseResourcesFolder, getArtifactId(artifactName) + separator + "META-INF");
      generatedResourcesDirectory.mkdirs();

      if (!isAlreadyAppendedURL(generatedResourcesDirectory.getParentFile().toURI().toURL(), classLoader)) {
        logger.debug("Checking if extension's metadata has to be generated on artifact: '{}'", artifactName);
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(true);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Extension.class));
        scanner.setResourceLoader(new PathMatchingResourcePatternResolver(buildURLClassLoaderWithFirstPath(classLoader)));
        Set<BeanDefinition> extensionsAnnotatedClasses = scanner.findCandidateComponents("");
        if (extensionsAnnotatedClasses.size() > 1) {
          throw new IllegalStateException(
                                          "While scanning class loader on '" + artifactName
                                              + "' for discovering @Extension classes annotated, more than one found. Only one should be discovered, found: "
                                              + extensionsAnnotatedClasses);
        }

        if (extensionsAnnotatedClasses.size() == 1) {
          String extensionClassName = extensionsAnnotatedClasses.iterator().next().getBeanClassName();
          logger.debug("Generating Extension metadata for extension class: '{}'", extensionClassName);
          Class extensionClass;
          try {
            extensionClass = classLoader.loadClass(extensionClassName);
          } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Cannot load Extension Class using artifactClassLoader: " + pluginClassLoader,
                                               e);
          }

          withContextClassLoader(classLoader, () -> extensionsInfrastructure.generateLoaderResources(
                                                                                                     extensionsInfrastructure
                                                                                                         .discoverExtension(
                                                                                                                            extensionClass,
                                                                                                                            new StaticVersionResolver(
                                                                                                                                                      getPluginVersion(
                                                                                                                                                                       artifactName))),
                                                                                                     generatedResourcesDirectory));

          Method method = findMethod(classLoader.getClass(), "addURL", URL.class);
          method.setAccessible(true);
          method.invoke(classLoader, generatedResourcesDirectory.getParentFile().toURI().toURL());
        } else {
          logger.debug("Already generated metadata for extension on artifact: '{}'", artifactName);
        }
      }
    }
  }

  /**
   * Checks if the {@link URL} was already added before, therefore it was already generated the metadata for this {@link ClassLoader} and
   * we don't need to generate it again.
   *
   * @param generatedResourcesURL the {@link URL} to check if is present in the class loader
   * @param pluginClassLoader {@link ClassLoader} for the plugin
   * @return {@code true} if it already contains the {@link URL}
   */
  private boolean isAlreadyAppendedURL(URL generatedResourcesURL, ClassLoader pluginClassLoader) {
    checkArgument(pluginClassLoader instanceof URLClassLoader, "pluginClassLoader should be a URLClassLoader");

    return stream(((URLClassLoader) pluginClassLoader).getURLs())
        .filter(url -> url.getFile().equals(generatedResourcesURL.getFile())).findAny().isPresent();
  }

  /**
   * Register extensions if the plugin artifact class loader has an extension descriptor manifest.
   *
   * @param extensionManager to register the extensions
   * @throws Exception if there was an error while registering the extensions
   */
  private void registerExtensions(ExtensionManagerAdapter extensionManager)
      throws Exception {
    for (Object pluginClassLoader : pluginsClassLoaders) {
      String artifactName = getArtifactName(pluginClassLoader);
      ClassLoader classLoader = getClassLoader(pluginClassLoader);
      URL manifestUrl = getExtensionManifest(classLoader);
      if (manifestUrl != null) {
        if (LOGGER.isDebugEnabled()) {
          LOGGER.debug("Discovered extension: {}", artifactName);
        }
        ExtensionManifest extensionManifest = extensionManager.parseExtensionManifestXml(manifestUrl);
        extensionManager.registerExtension(extensionManifest, classLoader);
      } else {
        LOGGER.debug(
                     "Discarding plugin artifact class loader with artifactName '{}' due to it doesn't have an extension descriptor",
                     artifactName);
      }
    }
  }

  private ClassLoader getClassLoader(Object pluginClassLoader)
      throws IllegalAccessException, InvocationTargetException, NoSuchMethodException {
    return (ClassLoader) pluginClassLoader.getClass().getMethod("getClassLoader").invoke(pluginClassLoader);
  }

  /**
   * Extensions plugin class loaders will have as first {@link URL} in its paths the target/classes, so it gets this first entry
   * in its paths and instantiates a new {@link URLClassLoader} with it.
   *
   * @param classLoader {@link ClassLoader} the plugin class loader
   * @return {@link URLClassLoader} with the first path from the passed class loader or just without any {@link URL}s if original didn't have any {@link URL}s
   */
  private ClassLoader buildURLClassLoaderWithFirstPath(ClassLoader classLoader) {
    checkArgument(classLoader instanceof URLClassLoader, "classLoader should be a URLClassLoader");

    Field field = findField(classLoader.getClass(), "ucp");
    field.setAccessible(true);
    try {
      URLClassPath urlClassPath = (URLClassPath) field.get(classLoader);

      field = findField(urlClassPath.getClass(), "path");
      field.setAccessible(true);
      List<URL> paths = (List<URL>) field.get(urlClassPath);
      if (paths.isEmpty() || !(paths.size() >= 1)) {
        return new URLClassLoader(new URL[0], null);
      }
      return new URLClassLoader(new URL[] {paths.get(0)}, null);
    } catch (IllegalAccessException e) {
      throw new RuntimeException("Error while getting first path from class loader", e);
    }
  }

  private String getArtifactName(Object pluginClassLoader)
      throws IllegalAccessException, InvocationTargetException, NoSuchMethodException {
    return (String) pluginClassLoader.getClass().getMethod("getArtifactName").invoke(pluginClassLoader);
  }

  private String getArtifactId(String artifactClassLoaderName) {
    return artifactClassLoaderName.split(":")[1];
  }

  private String getPluginVersion(String artifactClassLoaderName) {
    return artifactClassLoaderName.split(":")[3];
  }

  /**
   * Gets the extension manifest as {@link URL}
   *
   * @param classLoader the plugin {@link ClassLoader} to look for the resource
   * @return a {@link URL} or null if it is not present
   * @throws NoSuchMethodException if findResources {@link Method} is no found by reflection
   * @throws IllegalAccessException if findResources {@link Method} cannot be accessed
   * @throws InvocationTargetException if findResources {@link Method} throws an error
   */
  private URL getExtensionManifest(final ClassLoader classLoader)
      throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
    Method findResourceMethod = classLoader.getClass().getMethod("findResources", String.class);
    findResourceMethod.setAccessible(true);
    Enumeration<URL> enumeration =
        (Enumeration<URL>) findResourceMethod.invoke(classLoader, "META-INF/" + EXTENSION_MANIFEST_FILE_NAME);
    File generatedResourcesBaseFolder = getGeneratedResourcesBase();
    while (enumeration.hasMoreElements()) {
      URL found = enumeration.nextElement();
      File folder = new File(found.getFile()).getParentFile().getParentFile().getParentFile();
      if (folder.equals(generatedResourcesBaseFolder)) {
        return found;
      }
    }
    return null;
  }

  /**
   * Creates an {@link ExtensionManagerAdapter} to be used for registering the extensions.
   *
   * @param muleContext a {@link MuleContext} needed for creating the manager
   * @return an {@link ExtensionManagerAdapter}
   * @throws InitialisationException if an error occurrs while initializing the manager.
   */
  private ExtensionManagerAdapter createExtensionManager(final MuleContext muleContext) throws InitialisationException {
    try {
      return extensionManagerAdapterFactory.createExtensionManager(muleContext);
    } catch (Exception e) {
      throw new InitialisationException(e, muleContext);
    }
  }

  /**
   * Creates the {@value #GENERATED_TEST_SOURCES} inside the target folder to put metadata files for extensions. If no exists, it
   * will create it.
   *
   * @return {@link File} baseResourcesFolder to write extensions metadata.
   */
  private File getGeneratedResourcesBase() {
    File baseResourcesFolder = new File(targetFolder, GENERATED_TEST_SOURCES);
    baseResourcesFolder.mkdir();
    return baseResourcesFolder;
  }
}
