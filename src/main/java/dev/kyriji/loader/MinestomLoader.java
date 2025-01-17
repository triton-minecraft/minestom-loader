package dev.kyriji.loader;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

public class MinestomLoader {
	private static final Logger logger = LoggerFactory.getLogger(MinestomLoader.class);
	private static final String MODULES_FOLDER = "modules";

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
				loadModule(jarFile);
			} catch (Exception e) {
				logger.error("Failed to load module: {}", jarFile.getName(), e);
			}
		}
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

	private void loadModule(File jarFile) throws IOException, ClassNotFoundException,
			NoSuchMethodException, InvocationTargetException, InstantiationException, IllegalAccessException {

		logger.info("Loading module: {}", jarFile.getName());

		String mainClassName = getMainClassFromManifest(jarFile);
		if (mainClassName == null) {
			logger.error("No Main-Class specified in manifest for {}", jarFile.getName());
			return;
		}

		URLClassLoader moduleClassLoader = createModuleClassLoader(jarFile);
		Class<?> mainClass = loadMainClass(moduleClassLoader, mainClassName);
		invokeMainMethod(mainClass);
	}

	private String getMainClassFromManifest(File jarFile) throws IOException {
		try (JarFile jar = new JarFile(jarFile)) {
			Manifest manifest = jar.getManifest();
			if (manifest == null) {
				return null;
			}
			return manifest.getMainAttributes().getValue("Main-Class");
		}
	}

	private URLClassLoader createModuleClassLoader(File jarFile) throws IOException {
		return new URLClassLoader(
				new URL[]{jarFile.toURI().toURL()},
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