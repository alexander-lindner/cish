package org.alindner.cish.compiler.postcompiler;


import lombok.extern.log4j.Log4j2;
import org.alindner.cish.compiler.Props;
import org.alindner.cish.compiler.ScriptMetaInfo;
import org.alindner.cish.compiler.exceptions.CishCompileException;
import org.alindner.cish.compiler.exceptions.CishException;
import org.alindner.cish.compiler.postcompiler.extension.ExtensionManager;
import org.alindner.cish.compiler.utils.CishPath;
import org.alindner.cish.compiler.utils.Utils;
import org.apache.commons.io.FilenameUtils;

import javax.tools.*;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

/**
 * compile a java file within the cish environment
 *
 * @author alindner
 */
@Log4j2
public class PostCompiler {
	private final List<Path>     listOfModules = new ArrayList<>();
	private final ModuleManager  moduleManager;
	private final ScriptMetaInfo script;

	public PostCompiler(final ExtensionManager manager, final ScriptMetaInfo script) {
		this.script = script;
		this.moduleManager = new ModuleManager(script.getScript(), manager);
	}

	/**
	 * returns the class name of a given file
	 *
	 * @param file jar or java file
	 *
	 * @return class name
	 */
	static List<String> fileToClass(final Path file) throws CishException {
		switch (FilenameUtils.getExtension(file.toString())) {
			case "java":
				final List<String> list = new ArrayList<>();
				try {
					final Matcher matcher = Props.regexClassPattern.matcher(Files.readString(file));
					while (matcher.find()) {
						list.add(matcher.group(2));
					}
				} catch (final IOException e) {
					throw new CishException("Couldn't extract filename from java file.", e);
				}
				return list;
			case "jar":
				return Utils.getClassesFromJar(file.toAbsolutePath().toString())
				            .stream()
				            .filter(s -> !s.contains("$"))
				            .collect(Collectors.toList());
		}
		return List.of();
	}

	/**
	 * It compiles the given java file, which represents the java version of the origin cish file
	 *
	 * @param moduleList list of modules
	 *
	 * @throws IOException          error when copying files.
	 * @throws CishCompileException error when compiling the java file
	 * @throws URISyntaxException   something strange happened when trying to access the executed file itself
	 */
	public void compile(final List<Path> moduleList) throws IOException, URISyntaxException, CishCompileException {
		this.listOfModules.addAll(moduleList);

		final javax.tools.JavaCompiler            compiler    = ToolProvider.getSystemJavaCompiler();
		final DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		final StandardJavaFileManager             fileManager = compiler.getStandardFileManager(diagnostics, Locale.getDefault(), Charset.defaultCharset());

		fileManager.setLocation(
				StandardLocation.CLASS_PATH,
				Collections.singletonList(CishPath.modulePath(this.script.getScript()).toFile())
		);

		if (Files.notExists(CishPath.outPath(this.script.getRootScript()))) {
			Files.createDirectories(CishPath.outPath(this.script.getRootScript()));
		}
		final String modulePathString = this.listOfModules.stream().map(path -> path.toAbsolutePath().toString()).collect(Collectors.joining(":"));
		final javax.tools.JavaCompiler.CompilationTask compilerTask = compiler.getTask(
				null,
				fileManager,
				diagnostics,
				Arrays.asList(
						"-d",
						CishPath.outPath(this.script.getScript()).toString(),
						"-p",
						modulePathString,
						"--module-source-path",
						CishPath.ofCishFile(this.script.getScript()).toString(),
						"--module",
						"cishResult"
				),
				null,
				null
		);

		if (!compilerTask.call()) {
			diagnostics.getDiagnostics().forEach(diagnostic -> System.err.format("Error on line %d in %s", diagnostic.getLineNumber(), diagnostic));
			throw new CishCompileException("Could not compile file. Something during java compilation failed.");
		}
	}

	/**
	 * execute the compiled java code
	 *
	 * @param simpleParameters the parameters like -version
	 * @param argsList         the parameters like `0.3.2`
	 * @param parameters       the parameters like --version=test
	 */
	public void run(final List<String> simpleParameters, final List<String> argsList, final Map<String, String> parameters) {
		final ModuleLayer layer = this.moduleManager.getLayer();
		try {
			final Class<?> cls  = Class.forName(this.script.getPkg() + ".Main", true, layer.findLoader("cishResult"));
			final Method   meth = cls.getMethod("main", Path.class, List.class, List.class, Map.class);
			meth.invoke(null, this.script.getScript(), simpleParameters, argsList, parameters);
		} catch (final ClassNotFoundException e) {
			PostCompiler.log.fatal("Couldn't found the main class. This may be a bug.", e);
		} catch (final NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
			PostCompiler.log.fatal("Couldn't access the main class and its main method. This may be a bug.", e);
		}
	}

	/**
	 * the main method for compiling java code to byte code
	 *
	 * @param imports class which should be imported
	 *
	 * @throws IOException when compiling fails
	 */
	public void compileJava(final List<String> imports) throws IOException {
		this.putJavaContentToFile();
		this.putBashContentToFile();
		this.script.getImports().addAll(imports);
		this.prependsImports();

		final ArrayList<Path> listOfAllScripts = new ArrayList<>();
		listOfAllScripts.add(this.script.getScript());
		listOfAllScripts.addAll(
				this.script.getRequires().stream()
				           .map(Path::of)
				           .collect(Collectors.toList())
		);
		if (this.script.isRoot()) {
			listOfAllScripts.stream()
			                .map(CishPath::getCompileDirOfShellScript)
			                .forEach(f -> {
				                try {
					                this.compile(this.moduleManager.getModulePathsForCompiler());
				                } catch (final Exception e) {
					                PostCompiler.log.error("Couldn't compile file " + this.script.getScript(), e);
				                }
			                });
		}
	}

	/**
	 * compiles the <code>loads</code> statement
	 * <p>
	 * therefore it will download or copy the source file to the cache directory (classpath) and add imports to the resulting java file
	 *
	 * @throws IOException error when downloading, moving and working with urls/uris
	 */
	private void prependsImports() throws IOException {
		final AtomicReference<String> content = new AtomicReference<>("");
		final Map<String, Path> l = this.script.getLoads()
		                                       .stream()
		                                       .map(s -> {
			                                       final URI      fileName = URI.create(s);
			                                       final String[] tmp      = fileName.getPath().split("/");
			                                       final Path     target   = CishPath.ofCishFile(this.script.getRootScript()).resolve(tmp[tmp.length - 1]);

			                                       if (fileName.getScheme().equals("http") || fileName.getScheme().equals("https")) {
				                                       try {
//							Files.copy(new URL(s), target);
					                                       PostCompiler.log.debug("todo");
					                                       throw new IOException();
				                                       } catch (final IOException e) {
					                                       PostCompiler.log.error("Failed creating and downloading a url form string", e);
				                                       }
			                                       } else {
				                                       try {
					                                       Files.copy(Path.of(fileName), target);
				                                       } catch (final IOException e) {
					                                       PostCompiler.log.error("Failed copying the file to the cached target dir", e);
				                                       }
			                                       }
			                                       if (Files.exists(target)) {
				                                       return Map.ofEntries(Map.entry(s, target));
			                                       } else {
				                                       return new HashMap<String, Path>();
			                                       }
		                                       })
		                                       .flatMap(stringFileMap -> stringFileMap.entrySet().stream())
		                                       .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
		this.script.getImports()
		           .stream()
		           .filter(Objects::nonNull)
		           .map(s -> {
			           final List<String> list = new ArrayList<>();
			           if (s.startsWith("*.")) {
				           final Path f = l.get(s.substring(2));
				           try {
					           list.addAll(PostCompiler.fileToClass(f));
				           } catch (final CishException e) {
					           PostCompiler.log.error(e);
				           }
			           } else {
				           list.addAll(List.of(s));
			           }
			           return list;
		           })
		           .flatMap(Collection::stream)
		           .filter(Objects::nonNull)
		           .forEach(s -> content.set(String.format("%s\n import %s;", content.get(), s)));

		final List<String> tmpContent = Files.readAllLines(this.script.getJavaFile());
		tmpContent.add(1, content.get());

		Files.write(this.script.getJavaFile(), tmpContent);
		Files.write(
				this.script.getModuleInfo(),
				String.format(
						"module cishResult {%s\n\texports main;\n}",
						this.moduleManager.getRequireString()
				).getBytes()
		); //todo gets overridden with subscripts. Content should not change
	}

	/**
	 * puts the compiled cish code to a file in the cache directory (classpath)
	 *
	 * @throws IOException write error
	 */
	private void putJavaContentToFile() throws IOException {
		for (final Map.Entry<String, String> entry : this.script.getJavaContent().entrySet()) {
			final Path currentFile = CishPath.ofPackage(this.script.getRootScript(), this.script.getPkg()).resolve(entry.getKey() + ".java");
			Files.write(currentFile, entry.getValue().getBytes(StandardCharsets.UTF_8));
		}
	}

	/**
	 * puts the used inline bash code to a file in the cache directory (classpath)
	 *
	 * @throws IOException write error
	 */
	private void putBashContentToFile() throws IOException {
		for (final Map.Entry<String, String> entry : this.script.getBash().entrySet()) {

			Files.write(
					CishPath.ofBashScript(this.script.getRootScript(), entry.getKey()),
					String.format("#!/bin/bash \n%s", entry.getValue()).getBytes(StandardCharsets.UTF_8)
			);
		}
	}
}
