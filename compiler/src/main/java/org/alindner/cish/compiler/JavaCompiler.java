package org.alindner.cish.compiler;

import lombok.extern.log4j.Log4j2;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.util.*;

@Log4j2
public class JavaCompiler {
	public static void compile(final File sourceFile) throws Exception {
		final File classesDir;
		final File sourceDir = classesDir = new File(sourceFile.getParent());

		JavaCompiler.copyLangClasses(sourceDir);

		final javax.tools.JavaCompiler            compiler    = ToolProvider.getSystemJavaCompiler();
		final DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		final StandardJavaFileManager             fileManager = compiler.getStandardFileManager(diagnostics, Locale.getDefault(), null);
		final List<JavaFileObject>                javaObjects = JavaCompiler.scanRecursivelyForJavaObjects(sourceDir, fileManager);

		if (javaObjects.size() == 0) {
			throw new Exception(String.format("There are no source files to compile in %s", sourceDir.getAbsolutePath()));
		}
		final String[]         compileOptions     = new String[]{"-d", classesDir.getAbsolutePath()};
		final Iterable<String> compilationOptions = Arrays.asList(compileOptions);

		final javax.tools.JavaCompiler.CompilationTask compilerTask = compiler.getTask(null, fileManager, diagnostics, compilationOptions, null, javaObjects);

		if (!compilerTask.call()) {
			diagnostics.getDiagnostics().forEach(diagnostic -> System.err.format("Error on line %d in %s", diagnostic.getLineNumber(), diagnostic));
			throw new Exception("Could not compile project");
		}
	}

	private static void copyLangClasses(final File sourceDir) throws IOException, URISyntaxException {
		JavaCompiler.copyLangClasses(sourceDir, JavaCompiler.class.getPackageName().replaceAll("\\.", "/").replace("/compiler", "/lang"));
	}

	private static void copyLangClasses(final File sourceDir, final String base) throws IOException, URISyntaxException {
		final Path       compilerPath = Paths.get(JavaCompiler.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		final FileSystem fileSystem   = FileSystems.newFileSystem(compilerPath, JavaCompiler.class.getClassLoader());

		for (final Path rootDirectory : fileSystem.getRootDirectories()) {
			final Iterator<Path> it = Files.walk(rootDirectory).filter(path -> path.toString().startsWith(String.format("/%s", base))).iterator();
			while (it.hasNext()) {
				final Path path = it.next();
				if (!path.toString().endsWith(".class")) {
					JavaCompiler.copyLangClasses(sourceDir, path.toString() + "/");
				} else {
					final String name   = new File(path.toString()).getName();
					final File   target = new File(sourceDir, new File(path.toString()).getParent());
					target.mkdirs();
					Files.copy(
							Files.newInputStream(path),
							Paths.get(new File(target, name).getAbsolutePath()),
							StandardCopyOption.REPLACE_EXISTING
					);
				}
			}
		}
	}

	private static List<JavaFileObject> scanRecursivelyForJavaObjects(final File dir, final StandardJavaFileManager fileManager) {
		final List<JavaFileObject> javaObjects = new LinkedList<>();
		final File[]               files       = dir.listFiles();
		assert files != null;
		Arrays.stream(files).forEach(file -> {
			if (file.isDirectory()) {
				javaObjects.addAll(JavaCompiler.scanRecursivelyForJavaObjects(file, fileManager));
			} else if (file.isFile() && file.getName().toLowerCase().endsWith(".java")) {
				javaObjects.add(JavaCompiler.readJavaObject(file, fileManager));
			}
		});
		return javaObjects;
	}


	private static JavaFileObject readJavaObject(final File file, final StandardJavaFileManager fileManager) {
		final Iterator<? extends JavaFileObject> it = fileManager.getJavaFileObjects(file).iterator();
		if (it.hasNext()) {
			return it.next();
		}
		throw new RuntimeException(String.format("Could not load %s java file object", file.getAbsolutePath()));
	}

}