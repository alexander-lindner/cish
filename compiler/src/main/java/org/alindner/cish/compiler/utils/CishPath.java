package org.alindner.cish.compiler.utils;

import lombok.extern.log4j.Log4j2;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Log4j2
public class CishPath {
	static final Path home          = Path.of(System.getProperty("user.home"));
	static final Path base          = CishPath.home.resolve(".cish");
	static final Path baseExtension = CishPath.base.resolve("extensions");
	static final Path baseCompiled  = CishPath.base.resolve("cache/compiled");
	final static Path tmp           = CishPath.base.resolve("tmp");
	final static Path cacheDir      = CishPath.base.resolve("cache");
	static {
		try {
			if (Files.notExists(CishPath.tmp)) {
				Files.createDirectories(CishPath.tmp);
			}
			if (Files.notExists(CishPath.cacheDir)) {
				Files.createDirectories(CishPath.cacheDir);
			}
			if (Files.notExists(CishPath.baseCompiled)) {
				Files.createDirectories(CishPath.baseCompiled);
			}
		} catch (final IOException e) {
			CishPath.log.fatal("Couldn't create directory.", e);
			throw new Error("Unable to create the directory inside the home directory", e);
		}
	}
	public static Path of(final String relativePath) {
		return CishPath.base.resolve(relativePath).toAbsolutePath();
	}

	public static Path ofExtensions(final String relativePath) {
		return CishPath.baseExtension.resolve(relativePath).toAbsolutePath();
	}

	public static Path ofCompiled(final String relativePath) {
		return CishPath.baseCompiled.resolve(relativePath).toAbsolutePath();
	}

	public static Path ofCacheDir(final String relativePath) {
		return CishPath.cacheDir.resolve(relativePath).toAbsolutePath();
	}

	public static Path ofTmp(final String relativePath) {
		return CishPath.tmp.resolve(relativePath).toAbsolutePath();
	}

	public static Path ofCishFile(final Path cishFile) {
		return CishPath.baseCompiled.resolve(CishPath.getCompileDirOfShellScript(cishFile)).toAbsolutePath();
	}

	public static Path ofBashScript(final Path cishFile, final String bashName) {
		final Path d = CishPath.ofCishFile(cishFile).resolve("bash");
		if (!Files.isDirectory(d)) {
			try {
				Files.createDirectories(d);
			} catch (final IOException e) {
				CishPath.log.fatal("Couldn't create directory.", e);
				throw new Error("Unable to create the directory inside the home directory", e);
			}
		}
		return CishPath.ofCishFile(cishFile).resolve("bash").resolve(bashName);
	}

	public static Path modulePath(final Path cishFile) {
		final Path mainPath = CishPath.ofCishFile(cishFile).resolve("cishResult");
		if (Files.notExists(mainPath)) {
			try {
				Files.createDirectories(mainPath);
			} catch (final IOException e) {
				CishPath.log.fatal("Couldn't create directory.", e);
				throw new Error("Unable to create the directory inside the home directory", e);
			}
		}
		return mainPath;
	}

	public static Path mainPackage(final Path cishFile) {
		return CishPath.ofPackage(cishFile, "main");
	}

	public static Path mainFile(final Path cishFile) {
		return CishPath.mainPackage(cishFile).resolve("Main.java");
	}

	public static Path outPath(final Path cishFile) {
		return CishPath.ofCishFile(cishFile).resolve("out");
	}

	public static Path moduleInfoFile(final Path cishFile) {
		return CishPath.modulePath(cishFile).resolve("module-info.java");
	}

	/**
	 * get the cached base dir of the cish file
	 *
	 * @param file cish file
	 *
	 * @return cached dir
	 */
	public static Path getCompileDirOfShellScript(final Path file) {
		return CishPath.getCompileDirOfShellScript(CishPath.baseCompiled, file);
	}

	/**
	 * get the cached base dir of the cish file if it is a sub dir
	 *
	 * @param file   cish file
	 * @param parent parent dir
	 *
	 * @return cached dir
	 */
	public static Path getCompileDirOfShellScript(final Path parent, final Path file) {
		final Path sourceFile = parent.resolve("p" + Utils.hash(file.toAbsolutePath().getFileName().toString()));
		try {
			if (Files.notExists(sourceFile)) {
				Files.createDirectory(sourceFile);
			}
		} catch (final IOException e) {
			CishPath.log.fatal("Couldn't create compile directory:{}", sourceFile);
			throw new Error(e);
		}
		return sourceFile;
	}

	public static Path ofPackage(final Path cishFile, final String pkg) {
		final Path main = CishPath.modulePath(cishFile).resolve(pkg);
		if (Files.notExists(main)) {
			try {
				Files.createDirectories(main);
			} catch (final IOException e) {
				CishPath.log.fatal("Couldn't create directory.", e);
				throw new Error("Unable to create the directory inside the home directory", e);
			}
		}
		return main;
	}
}
