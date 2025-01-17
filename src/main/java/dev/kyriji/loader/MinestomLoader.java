package dev.kyriji.loader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

public class MinestomLoader {
	private static final Logger logger = LoggerFactory.getLogger(MinestomLoader.class);
	private static final String MODULES_FOLDER = "modules";
	private static final String DEPENDENCIES_ATTRIBUTE = "Module-Dependencies";
	private final Map<String, ModuleInfo> loadedModules = new HashMap<>();

	private static class ModuleInfo {
		final File jarFile;
		final String moduleName;
		final Set<String> dependencies;
		URLClassLoader classLoader;
		boolean isLoaded;

		ModuleInfo(File jarFile, String moduleName, Set<String> dependencies) {
			this.jarFile = jarFile;
			this.moduleName = moduleName;
			this.dependencies = dependencies;
			this.isLoaded = false;
		}
	}

	public static void main(String[] args) {
		MinestomLoader loader = new MinestomLoader();
		try {
			loader.initializeAndLoad();
		} catch (IOException e) {
			logger.error("Failed to initialize module loading", e);
		}
	}

	public void initializeAndLoad() throws IOException {
		File modulesFolder = createModulesFolder();
		File[] jarFiles = getJarFiles(modulesFolder);

		if (jarFiles == null || jarFiles.length == 0) {
			logger.warn("No modules found in {}", modulesFolder.getAbsolutePath());
			return;
		}

		for (File jarFile : jarFiles) {
			try {
				registerModule(jarFile);
			} catch (Exception e) {
				logger.error("Failed to register module: {}", jarFile.getName(), e);
			}
		}

		for (ModuleInfo moduleInfo : loadedModules.values()) {
			try {
				loadModuleWithDependencies(moduleInfo);
			} catch (Exception e) {
				logger.error("Failed to load module: {}", moduleInfo.moduleName, e);
			}
		}
	}

	private void registerModule(File jarFile) throws IOException {
		String moduleName = getModuleNameFromManifest(jarFile);
		Set<String> dependencies = getDependenciesFromManifest(jarFile);

		if (moduleName != null) {
			loadedModules.put(moduleName, new ModuleInfo(jarFile, moduleName, dependencies));
			logger.info("Registered module: {} with dependencies: {}", moduleName, dependencies);
		} else {
			logger.error("No Module-Name specified in manifest for {}", jarFile.getName());
		}
	}

	private void loadModuleWithDependencies(ModuleInfo moduleInfo) throws Exception {
		if (moduleInfo.isLoaded) {
			return;
		}

		Set<String> visitedModules = new HashSet<>();
		if (hasCircularDependencies(moduleInfo.moduleName, visitedModules)) {
			throw new Exception("Circular dependency detected for module: " + moduleInfo.moduleName);
		}

		for (String dependency : moduleInfo.dependencies) {
			ModuleInfo dependencyInfo = loadedModules.get(dependency);
			if (dependencyInfo == null) {
				throw new Exception("Missing dependency: " + dependency + " for module: " + moduleInfo.moduleName);
			}
			loadModuleWithDependencies(dependencyInfo);
		}

		loadModule(moduleInfo);
	}

	private boolean hasCircularDependencies(String moduleName, Set<String> visitedModules) {
		if (!visitedModules.add(moduleName)) {
			return true;
		}

		ModuleInfo moduleInfo = loadedModules.get(moduleName);
		for (String dependency : moduleInfo.dependencies) {
			ModuleInfo dependencyInfo = loadedModules.get(dependency);
			if (dependencyInfo != null && hasCircularDependencies(dependency, new HashSet<>(visitedModules))) {
				return true;
			}
		}

		return false;
	}

	private String getModuleNameFromManifest(File jarFile) throws IOException {
		try (JarFile jar = new JarFile(jarFile)) {
			Manifest manifest = jar.getManifest();
			if (manifest != null) {
				return manifest.getMainAttributes().getValue("Module-Name");
			}
		}
		return null;
	}

	private Set<String> getDependenciesFromManifest(File jarFile) throws IOException {
		try (JarFile jar = new JarFile(jarFile)) {
			Manifest manifest = jar.getManifest();
			if (manifest != null) {
				String dependencies = manifest.getMainAttributes().getValue(DEPENDENCIES_ATTRIBUTE);
				if (dependencies != null && !dependencies.trim().isEmpty()) {
					return new HashSet<>(Arrays.asList(dependencies.split(",")));
				}
			}
		}
		return new HashSet<>();
	}

	private void loadModule(ModuleInfo moduleInfo) throws Exception {
		if (moduleInfo.isLoaded) {
			return;
		}

		logger.info("Loading module: {}", moduleInfo.moduleName);

		String mainClassName = getMainClassFromManifest(moduleInfo.jarFile);
		if (mainClassName == null) {
			throw new Exception("No Main-Class specified in manifest for " + moduleInfo.moduleName);
		}

		URL[] urls = getDependencyUrls(moduleInfo);
		moduleInfo.classLoader = createModuleClassLoader(urls);

		Class<?> mainClass = loadMainClass(moduleInfo.classLoader, mainClassName);
		invokeMainMethod(mainClass);

		moduleInfo.isLoaded = true;
		logger.info("Successfully loaded module: {}", moduleInfo.moduleName);
	}

	private URL[] getDependencyUrls(ModuleInfo moduleInfo) throws Exception {
		List<URL> urls = new ArrayList<>();
		urls.add(moduleInfo.jarFile.toURI().toURL());

		for (String dependency : moduleInfo.dependencies) {
			ModuleInfo dependencyInfo = loadedModules.get(dependency);
			if (dependencyInfo == null) {
				throw new Exception("Missing dependency: " + dependency);
			}
			urls.add(dependencyInfo.jarFile.toURI().toURL());
		}

		return urls.toArray(new URL[0]);
	}

	private File createModulesFolder() {
		File modulesFolder = new File(MODULES_FOLDER);
		if (!modulesFolder.exists()) {
			logger.info("Creating modules folder at {}", modulesFolder.getAbsolutePath());
			if (!modulesFolder.mkdir()) {
				logger.error("Failed to create modules folder");
			}
		}
		return modulesFolder;
	}

	private File[] getJarFiles(File folder) {
		return folder.listFiles((dir, name) -> name.toLowerCase().endsWith(".jar"));
	}

	private String getMainClassFromManifest(File jarFile) throws IOException {
		try (JarFile jar = new JarFile(jarFile)) {
			Manifest manifest = jar.getManifest();
			if (manifest == null) {
				return null;
			}
			return manifest.getMainAttributes().getValue(Attributes.Name.MAIN_CLASS);
		}
	}

	private URLClassLoader createModuleClassLoader(URL[] urls) {
		return new URLClassLoader(
				urls,
				MinestomLoader.class.getClassLoader()
		);
	}

	private Class<?> loadMainClass(URLClassLoader classLoader, String mainClassName)
			throws ClassNotFoundException {
		logger.debug("Loading main class: {}", mainClassName);
		return Class.forName(mainClassName, true, classLoader);
	}

	private void invokeMainMethod(Class<?> mainClass) throws NoSuchMethodException,
			IllegalAccessException, InvocationTargetException {
		Method mainMethod = mainClass.getDeclaredMethod("main", String[].class);
		logger.debug("Invoking main method for class: {}", mainClass.getName());
		mainMethod.invoke(null, (Object) new String[0]);
	}
}