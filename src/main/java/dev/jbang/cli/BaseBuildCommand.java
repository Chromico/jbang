package dev.jbang.cli;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Predicate;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.Index;
import org.jboss.jandex.Indexer;
import org.jboss.jandex.Type;
import org.jboss.shrinkwrap.resolver.api.maven.coordinate.MavenCoordinate;

import dev.jbang.dependencies.DependencyUtil;
import dev.jbang.net.JdkManager;
import dev.jbang.source.GroovyScriptSource;
import dev.jbang.source.JarSource;
import dev.jbang.source.RunContext;
import dev.jbang.source.ScriptSource;
import dev.jbang.source.Source;
import dev.jbang.spi.IntegrationManager;
import dev.jbang.spi.IntegrationResult;
import dev.jbang.util.JarUtil;
import dev.jbang.util.JavaUtil;
import dev.jbang.util.TemplateEngine;
import dev.jbang.util.Util;

import io.quarkus.qute.Template;
import picocli.CommandLine;

public abstract class BaseBuildCommand extends BaseScriptCommand {
	protected String javaVersion;

	public static final Type STRINGARRAYTYPE = Type.create(DotName.createSimple("[Ljava.lang.String;"),
			Type.Kind.ARRAY);
	public static final Type STRINGTYPE = Type.create(DotName.createSimple("java.lang.String"), Type.Kind.CLASS);
	public static final Type INSTRUMENTATIONTYPE = Type.create(
			DotName.createSimple("java.lang.instrument.Instrumentation"), Type.Kind.CLASS);

	@CommandLine.Mixin
	DependencyInfoMixin dependencyInfoMixin;

	@CommandLine.Option(names = { "-s", "--sources" }, description = "Add additional sources.")
	List<String> sources;

	@CommandLine.Option(names = { "-m",
			"--main" }, description = "Main class to use when running. Used primarily for running jar's.")
	String main;

	@CommandLine.Option(names = { "-j",
			"--java" }, description = "JDK version to use for running the script.")
	void setJavaVersion(String javaVersion) {
		if (!javaVersion.matches("\\d+[+]?")) {
			throw new IllegalArgumentException(
					"Invalid version, should be a number optionally followed by a plus sign");
		}
		this.javaVersion = javaVersion;
	}

	@CommandLine.Option(names = {
			"--cds" }, description = "If specified Class Data Sharing (CDS) will be used for building and running (requires Java 13+)", negatable = true)
	Boolean cds;

	Optional<Boolean> cds() {
		return Optional.ofNullable(cds);
	}

	@CommandLine.Option(names = {
			"-n", "--native" }, description = "Build using native-image")
	boolean nativeImage;

	@CommandLine.Option(names = { "--catalog" }, description = "Path to catalog file to be used instead of the default")
	File catalog;

	PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out));

	static Source buildIfNeeded(Source src, RunContext ctx) throws IOException {
		if (needsJar(src, ctx)) {
			src = build((ScriptSource) src, ctx);
		}
		return src;
	}

	static Source build(ScriptSource src, RunContext ctx) throws IOException {
		Source result = src;

		File outjar = src.getJarFile();
		boolean nativeBuildRequired = ctx.isNativeImage() && !getImageName(outjar).exists();
		IntegrationResult integrationResult = new IntegrationResult(null, null, null);
		String requestedJavaVersion = ctx.getJavaVersion() != null ? ctx.getJavaVersion() : src.getJavaVersion();
		// always build the jar for native mode
		// it allows integrations the options to produce the native image
		boolean buildRequired = false;
		if (Util.isFresh()) {
			Util.verboseMsg("Building as fresh build explicitly requested.");
			buildRequired = true;
		} else if (nativeBuildRequired) {
			Util.verboseMsg("Building as native build required.");
			buildRequired = true;
		} else if (outjar.canRead()) {
			// We already have a Jar, check if we can still use it
			JarSource jarSrc = src.asJarSource();

			if (jarSrc == null) {
				Util.verboseMsg("Building as previous built jar not found.");
				buildRequired = true;
			} else if (!jarSrc.isUpToDate()) {
				Util.verboseMsg("Building as previous build jar found but it or its dependencies not up-to-date.");
				buildRequired = true;
			} else if (JavaUtil.javaVersion(requestedJavaVersion) < JavaUtil.minRequestedVersion(
					jarSrc.getJavaVersion())) {
				Util.verboseMsg(
						String.format(
								"Building as requested Java version %s < than the java version used during last build %s",
								requestedJavaVersion, jarSrc.getJavaVersion()));
				buildRequired = true;
			} else {
				Util.verboseMsg("No build required. Reusing jar from " + jarSrc.getJarFile());
				result = ctx.importJarMetadataFor(jarSrc);
			}
		} else {
			Util.verboseMsg("Build required as " + outjar + " not readable or not found.");
			buildRequired = true;
		}

		if (buildRequired) {
			// set up temporary folder for compilation
			File tmpJarDir = new File(outjar.getParentFile(), outjar.getName() + ".tmp");
			Util.deletePath(tmpJarDir.toPath(), true);
			tmpJarDir.mkdirs();
			// do the actual building
			try {
				integrationResult = buildJar(src, ctx, tmpJarDir, outjar, requestedJavaVersion);
			} finally {
				// clean up temporary folder
				Util.deletePath(tmpJarDir.toPath(), true);
			}
		}

		if (nativeBuildRequired) {
			if (integrationResult.nativeImagePath != null) {
				Files.move(integrationResult.nativeImagePath, getImageName(outjar).toPath());
			} else {
				buildNative(src, ctx, outjar, requestedJavaVersion);
			}
		}

		return result;
	}

	// build with javac and then jar... todo: split up in more testable chunks
	public static IntegrationResult buildJar(ScriptSource src, RunContext ctx, File tmpJarDir, File outjar,
			String requestedJavaVersion) throws IOException {
		IntegrationResult integrationResult;
		List<String> optionList = new ArrayList<>();
		optionList.add(src.getCompilerBinary(requestedJavaVersion));
		optionList.addAll(src.getCompileOptions());
		String path = ctx.resolveClassPath(src);
		if (!Util.isBlankString(path)) {
			optionList.addAll(Arrays.asList("-classpath", path));
		}
		optionList.addAll(Arrays.asList("-d", tmpJarDir.getAbsolutePath()));

		// add source files to compile
		optionList.add(src.getResourceRef().getFile().getPath());
		optionList.addAll(ctx	.getAllSources(src)
								.stream()
								.map(x -> x.getResourceRef().getFile().getPath())
								.collect(Collectors.toList()));

		// add additional files
		src.copyFilesTo(tmpJarDir.toPath());

		Path pomPath = generatePom(src, ctx, tmpJarDir);

		Util.infoMsg("Building jar...");
		Util.verboseMsg("compile: " + String.join(" ", optionList));

		final ProcessBuilder processBuilder = new ProcessBuilder(optionList).inheritIO();
		if (src instanceof GroovyScriptSource) {
			processBuilder.environment().put("JAVA_HOME", JdkManager.getCurrentJdk(requestedJavaVersion).toString());
			processBuilder.environment().remove("GROOVY_HOME");
		}
		Process process = processBuilder.start();
		try {
			process.waitFor();
		} catch (InterruptedException e) {
			throw new ExitException(1, e);
		}

		if (process.exitValue() != 0) {
			throw new ExitException(1, "Error during compile");
		}

		ctx.setBuildJdk(JavaUtil.javaVersion(requestedJavaVersion));
		// todo: setting properties to avoid loosing properties in integration call.
		Properties old = System.getProperties();
		Properties temporay = new Properties(System.getProperties());
		for (Map.Entry<String, String> entry : ctx.getProperties().entrySet()) {
			System.setProperty(entry.getKey(), entry.getValue());
		}
		integrationResult = IntegrationManager.runIntegration(src.getAllRepositories(),
				ctx.getClassPath().getArtifacts(),
				tmpJarDir.toPath(), pomPath,
				src, ctx.isNativeImage());
		System.setProperties(old);

		if (ctx.getMainClass() == null) { // if non-null user forced set main
			if (integrationResult.mainClass != null) {
				ctx.setMainClass(integrationResult.mainClass);
			} else {
				searchForMain(src, ctx, tmpJarDir);
			}
		}
		ctx.setIntegrationOptions(integrationResult.javaArgs);
		createJarFile(src, ctx, tmpJarDir, outjar);
		return integrationResult;
	}

	public static void createJarFile(ScriptSource src, RunContext ctx, File path, File output) throws IOException {
		String mainclass = ctx.getMainClassOr(src);
		Manifest manifest = new Manifest();
		manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
		if (mainclass != null) {
			manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainclass);
		}

		if (src.isAgent()) {
			if (ctx.getPreMainClass() != null) {
				manifest.getMainAttributes().put(new Attributes.Name(Source.ATTR_PREMAIN_CLASS), ctx.getPreMainClass());
			}
			if (ctx.getAgentMainClass() != null) {
				manifest.getMainAttributes().put(new Attributes.Name(Source.ATTR_AGENT_CLASS), ctx.getAgentMainClass());
			}

			for (ScriptSource.KeyValue kv : src.getAllAgentOptions()) {
				if (Util.isBlankString(kv.getKey())) {
					continue;
				}
				Attributes.Name k = new Attributes.Name(kv.getKey());
				String v = kv.getValue() == null ? "true" : kv.getValue();
				manifest.getMainAttributes().put(k, v);
			}

			if (ctx.getClassPath() != null) {
				String bootClasspath = ctx.getClassPath().getManifestPath();
				if (!bootClasspath.isEmpty()) {
					manifest.getMainAttributes().put(new Attributes.Name(Source.ATTR_BOOT_CLASS_PATH), bootClasspath);
				}
			}
		} else {
			if (ctx.getClassPath() != null) {
				String classpath = ctx.getClassPath().getManifestPath();
				if (!classpath.isEmpty()) {
					manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, classpath);
				}
			}
		}

		// When persistent JVM args are set they are appended to any runtime
		// options set on the Source (that way persistent args can override
		// options set on the Source)
		List<String> rtArgs = ctx.getRuntimeOptionsMerged(src);
		String runtimeOpts = String.join(" ", escapeArguments(rtArgs));
		if (!runtimeOpts.isEmpty()) {
			manifest.getMainAttributes()
					.putValue(Source.ATTR_JBANG_JAVA_OPTIONS, runtimeOpts);
		}
		int buildJdk = ctx.getBuildJdk();
		if (buildJdk > 0) {
			String val = buildJdk >= 9 ? Integer.toString(buildJdk) : "1." + buildJdk;
			manifest.getMainAttributes().putValue(Source.ATTR_BUILD_JDK, val);
		}

		FileOutputStream target = new FileOutputStream(output);
		JarUtil.jar(target, path.listFiles(), null, null, manifest);
		target.close();
	}

	static private void buildNative(Source src, RunContext ctx, File outjar, String requestedJavaVersion)
			throws IOException {
		List<String> optionList = new ArrayList<>();
		optionList.add(resolveInGraalVMHome("native-image", requestedJavaVersion));

		optionList.add("-H:+ReportExceptionStackTraces");

		optionList.add("--enable-https");

		String classpath = ctx.resolveClassPath(src);
		if (!Util.isBlankString(classpath)) {
			optionList.add("--class-path=" + classpath);
		}

		optionList.add("-jar");
		optionList.add(outjar.toString());

		optionList.add(getImageName(outjar).toString());

		File nilog = File.createTempFile("jbang", "native-image");
		Util.verboseMsg("native-image: " + String.join(" ", optionList));
		Util.infoMsg("log: " + nilog.toString());

		Process process = new ProcessBuilder(optionList).inheritIO().redirectOutput(nilog).start();
		try {
			process.waitFor();
		} catch (InterruptedException e) {
			throw new ExitException(1, e);
		}

		if (process.exitValue() != 0) {
			throw new ExitException(1, "Error during native-image");
		}
	}

	/** based on jar what will the binary image name be. **/
	static protected File getImageName(File outjar) {
		if (Util.isWindows()) {
			return new File(outjar.toString() + ".exe");
		} else {
			return new File(outjar.toString() + ".bin");
		}
	}

	static public String findMainClass(Path base, Path classfile) {
		StringBuilder mainClass = new StringBuilder(classfile.getFileName().toString().replace(".class", ""));
		while (!classfile.getParent().equals(base)) {
			classfile = classfile.getParent();
			mainClass.insert(0, classfile.getFileName().toString() + ".");
		}
		return mainClass.toString();
	}

	private static String resolveInGraalVMHome(String cmd, String requestedVersion) {
		String newcmd = resolveInEnv("GRAALVM_HOME", cmd);

		if (newcmd.equals(cmd) &&
				!new File(newcmd).exists()) {
			return JavaUtil.resolveInJavaHome(cmd, requestedVersion);
		} else {
			return newcmd;
		}
	}

	private static String resolveInEnv(String env, String cmd) {
		if (System.getenv(env) != null) {
			if (Util.isWindows()) {
				cmd = cmd + ".exe";
			}
			return new File(System.getenv(env)).toPath().resolve("bin").resolve(cmd).toAbsolutePath().toString();
		} else {
			return cmd;
		}
	}

	// NB: This might not be a definitive list of safe characters
	static Pattern cmdSafeChars = Pattern.compile("[a-zA-Z0-9.,_+=:;@()-]*");
	// TODO: Figure out what the real list of safe characters is for PowerShell
	static Pattern pwrSafeChars = Pattern.compile("[a-zA-Z0-9.,_+=:;@()-]*");
	static Pattern shellSafeChars = Pattern.compile("[a-zA-Z0-9._+=:@%/-]*");

	/**
	 * Escapes list of arguments where necessary using the current OS' way of
	 * escaping
	 */
	static List<String> escapeOSArguments(List<String> args) {
		return args.stream().map(BaseBuildCommand::escapeOSArgument).collect(Collectors.toList());
	}

	/**
	 * Escapes list of arguments where necessary using a generic way of escaping
	 * (we'll just be using the Unix way)
	 */
	static List<String> escapeArguments(List<String> args) {
		return args.stream().map(BaseBuildCommand::escapeUnixArgument).collect(Collectors.toList());
	}

	static String escapeOSArgument(String arg) {
		switch (Util.getShell()) {
		case bash:
			return escapeUnixArgument(arg);
		case cmd:
			return escapeCmdArgument(arg);
		case powershell:
			return escapePowershellArgument(arg);
		}
		return arg;
	}

	static String escapeUnixArgument(String arg) {
		if (!shellSafeChars.matcher(arg).matches()) {
			arg = arg.replaceAll("(['])", "'\\\\''");
			arg = "'" + arg + "'";
		}
		return arg;
	}

	static String escapeArgsFileArgument(String arg) {
		if (!shellSafeChars.matcher(arg).matches()) {
			arg = arg.replaceAll("([\"'\\\\])", "\\\\$1");
			arg = "\"" + arg + "\"";
		}
		return arg;
	}

	static String escapeCmdArgument(String arg) {
		if (!cmdSafeChars.matcher(arg).matches()) {
			// Windows quoting is just weird
			arg = arg.replaceAll("([()!^<>&|% ])", "^$1");
			arg = arg.replaceAll("([\"])", "\\\\^$1");
			arg = "^\"" + arg + "^\"";
		}
		return arg;
	}

	static String escapePowershellArgument(String arg) {
		if (!pwrSafeChars.matcher(arg).matches()) {
			arg = arg.replaceAll("(['])", "''");
			arg = "'" + arg + "'";
		}
		return arg;
	}

	protected static void searchForMain(ScriptSource src, RunContext ctx, File tmpJarDir) {
		try {
			// using Files.walk method with try-with-resources
			try (Stream<Path> paths = Files.walk(tmpJarDir.toPath())) {
				List<Path> items = paths.filter(Files::isRegularFile)
										.filter(f -> !f.toFile().getName().contains("$"))
										.filter(f -> f.toFile().getName().endsWith(".class"))
										.collect(Collectors.toList());

				Indexer indexer = new Indexer();
				Index index;
				for (Path item : items) {
					try (InputStream stream = new FileInputStream(item.toFile())) {
						indexer.index(stream);
					}
				}
				index = indexer.complete();

				Collection<ClassInfo> classes = index.getKnownClasses();

				List<ClassInfo> mains = classes	.stream()
												.filter(src.getMainFinder())
												.collect(Collectors.toList());
				String mainName = src.getSuggestedMain();
				if (mains.size() > 1 && mainName != null) {
					List<ClassInfo> suggestedmain = mains	.stream()
															.filter(ci -> ci.simpleName().equals(mainName))
															.collect(Collectors.toList());
					if (!suggestedmain.isEmpty()) {
						mains = suggestedmain;
					}
				}

				if (!mains.isEmpty()) {
					ctx.setMainClass(mains.get(0).name().toString());
					if (mains.size() > 1) {
						Util.warnMsg(
								"Could not locate unique main() method. Use -m to specify explicit main method. Falling back to use first found: "
										+ mains	.stream()
												.map(x -> x.name().toString())
												.collect(Collectors.joining(",")));
					}
				}

				if (src.isAgent()) {
					Optional<ClassInfo> agentmain = classes	.stream()
															.filter(pubClass -> pubClass.method("agentmain",
																	STRINGTYPE,
																	INSTRUMENTATIONTYPE) != null
																	||
																	pubClass.method("agentmain",
																			STRINGTYPE) != null)
															.findFirst();

					if (agentmain.isPresent()) {
						ctx.setAgentMainClass(agentmain.get().name().toString());
					}

					Optional<ClassInfo> premain = classes	.stream()
															.filter(pubClass -> pubClass.method("premain",
																	STRINGTYPE,
																	INSTRUMENTATIONTYPE) != null
																	||
																	pubClass.method("premain",
																			STRINGTYPE) != null)
															.findFirst();

					if (premain.isPresent()) {
						ctx.setPreMainClass(premain.get().name().toString());
					}
				}
			}
		} catch (IOException e) {
			throw new ExitException(1, e);
		}
	}

	private Predicate<ClassInfo> getMainFinder() {
		return pubClass -> pubClass.method("main", BaseBuildCommand.STRINGARRAYTYPE) != null;
	}

	protected static Path generatePom(ScriptSource src, RunContext ctx, File tmpJarDir) throws IOException {
		Template pomTemplate = TemplateEngine.instance().getTemplate("pom.qute.xml");

		Path pomPath = null;
		if (pomTemplate == null) {
			// ignore
			Util.warnMsg("Could not locate pom.xml template");
		} else {
			String group = "group";
			String artifact = Util.getBaseName(src.getResourceRef().getFile().getName());
			String version = "999-SNAPSHOT";
			if (src.getGav().isPresent()) {
				MavenCoordinate coord = DependencyUtil.depIdToArtifact(
						DependencyUtil.gavWithVersion(src.getGav().get()));
				group = coord.getGroupId();
				artifact = coord.getArtifactId();
				version = coord.getVersion();
			}
			String pomfile = pomTemplate
										.data("baseName", Util.getBaseName(src.getResourceRef().getFile().getName()))
										.data("group", group)
										.data("artifact", artifact)
										.data("version", version)
										.data("description", src.getDescription().orElse(""))
										.data("dependencies", ctx.getClassPath().getArtifacts())
										.render();

			pomPath = new File(tmpJarDir, "META-INF/maven/" + group.replace(".", "/") + "/pom.xml").toPath();
			Files.createDirectories(pomPath.getParent());
			Util.writeString(pomPath, pomfile);
		}
		return pomPath;
	}

}
