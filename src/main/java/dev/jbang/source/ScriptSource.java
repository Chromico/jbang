package dev.jbang.source;

import static dev.jbang.util.JavaUtil.resolveInJavaHome;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jboss.jandex.ClassInfo;

import dev.jbang.Cache;
import dev.jbang.Settings;
import dev.jbang.cli.BaseBuildCommand;
import dev.jbang.cli.BaseCommand;
import dev.jbang.cli.ExitException;
import dev.jbang.dependencies.DependencyResolver;
import dev.jbang.dependencies.DependencyUtil;
import dev.jbang.dependencies.MavenRepo;
import dev.jbang.util.JavaUtil;
import dev.jbang.util.Util;

/**
 * A Script represents a Source (something runnable) in the form of a source
 * file. It's code that first needs to be compiled before it can be executed.
 * The Script extracts as much information from the source file as it can, like
 * all `//`-directives (eg. `//SOURCES`, `//DEPS`, etc.)
 *
 * NB: The Script contains/returns no other information than that which can be
 * induced from the source file. So all Scripts that refer to the same source
 * file will contain/return the exact same information.
 */
public class ScriptSource implements Source {

	private static final String DEPS_COMMENT_PREFIX = "//DEPS ";
	private static final String FILES_COMMENT_PREFIX = "//FILES ";
	private static final String SOURCES_COMMENT_PREFIX = "//SOURCES ";
	private static final String DESCRIPTION_COMMENT_PREFIX = "//DESCRIPTION ";
	private static final String GAV_COMMENT_PREFIX = "//GAV ";

	private static final String DEPS_ANNOT_PREFIX = "@Grab(";
	private static final Pattern DEPS_ANNOT_PAIRS = Pattern.compile("(?<key>\\w+)\\s*=\\s*\"(?<value>.*?)\"");
	private static final Pattern DEPS_ANNOT_SINGLE = Pattern.compile("@Grab\\(\\s*\"(?<value>.*)\"\\s*\\)");

	private static final String REPOS_COMMENT_PREFIX = "//REPOS ";
	private static final String REPOS_ANNOT_PREFIX = "@GrabResolver(";
	private static final Pattern REPOS_ANNOT_PAIRS = Pattern.compile("(?<key>\\w+)\\s*=\\s*\"(?<value>.*?)\"");
	private static final Pattern REPOS_ANNOT_SINGLE = Pattern.compile("@GrabResolver\\(\\s*\"(?<value>.*)\"\\s*\\)");

	private final ResourceRef resourceRef;
	private final String script;
	private final Function<String, String> replaceProperties;

	// Cached values
	private List<String> lines;
	private List<MavenRepo> repositories;
	private List<String> dependencies;
	private List<RefTarget> filerefs;
	private List<ScriptSource> sources;
	private List<KeyValue> agentOptions;
	private Optional<String> description = Optional.empty();
	private Optional<String> gav = Optional.empty();
	private File jar;
	private JarSource jarSource;

	public ScriptSource(String script, Function<String, String> replaceProperties) {
		this(ResourceRef.forFile(null), script, replaceProperties);
	}

	protected ScriptSource(ResourceRef resourceRef, Function<String, String> replaceProperties) {
		this(resourceRef, getBackingFileContent(resourceRef.getFile()), replaceProperties);
	}

	protected ScriptSource(ResourceRef resourceRef, String content, Function<String, String> replaceProperties) {
		this.resourceRef = resourceRef;
		this.script = content;
		this.replaceProperties = replaceProperties != null ? replaceProperties : Function.identity();
	}

	public List<String> getCompileOptions() {
		return collectOptions("JAVAC_OPTIONS");
	}

	public String getCompilerBinary(String requestedJavaVersion) {
		return resolveInJavaHome("javac", requestedJavaVersion);
	}

	public Predicate<ClassInfo> getMainFinder() {
		return pubClass -> pubClass.method("main", BaseBuildCommand.STRINGARRAYTYPE) != null;
	}

	protected String getMainExtension() {
		return ".java";
	}

	@Override
	public ResourceRef getResourceRef() {
		return resourceRef;
	}

	public List<String> getLines() {
		if (lines == null && script != null) {
			lines = Arrays.asList(script.split("\\r?\\n"));
		}
		return lines;
	}

	public Optional<String> getJavaPackage() {
		if (script != null) {
			return Util.getSourcePackage(script);
		} else {
			return Optional.empty();
		}
	}

	@Override
	public List<String> getAllDependencies() {
		if (dependencies == null) {
			dependencies = collectAll(ScriptSource::collectDependencies);
		}
		return dependencies;
	}

	public List<String> collectDependencies() {
		// Make sure that dependencies declarations are well formatted
		if (getLines().stream().anyMatch(it -> it.startsWith("// DEPS"))) {
			throw new IllegalArgumentException("Dependencies must be declared by using the line prefix //DEPS");
		}

		return getLines()	.stream()
							.filter(ScriptSource::isDependDeclare)
							.flatMap(ScriptSource::extractDependencies)
							.map(replaceProperties)
							.collect(Collectors.toList());
	}

	static boolean isDependDeclare(String line) {
		return line.startsWith(DEPS_COMMENT_PREFIX) || line.contains(DEPS_ANNOT_PREFIX);
	}

	static Stream<String> extractDependencies(String line) {
		if (line.startsWith(DEPS_COMMENT_PREFIX)) {
			return Arrays.stream(line.split(" // ")[0].split("[ ;,]+")).skip(1).map(String::trim);
		}

		if (line.contains(DEPS_ANNOT_PREFIX)) {
			int commentOrEnd = line.indexOf("//");
			if (commentOrEnd < 0) {
				commentOrEnd = line.length();
			}
			if (line.indexOf(DEPS_ANNOT_PREFIX) > commentOrEnd) {
				// ignore if on line that is a comment
				return Stream.of();
			}

			Map<String, String> args = new HashMap<>();

			Matcher matcher = DEPS_ANNOT_PAIRS.matcher(line);
			while (matcher.find()) {
				args.put(matcher.group("key"), matcher.group("value"));
			}
			if (!args.isEmpty()) {
				// groupId:artifactId:version[:classifier][@type]
				String gav = Stream.of(
						args.get("group"),
						args.get("module"),
						args.get("version"),
						args.get("classifier")).filter(Objects::nonNull).collect(Collectors.joining(":"));
				if (args.containsKey("ext")) {
					gav = gav + "@" + args.get("ext");
				}
				return Stream.of(gav);
			} else {
				matcher = DEPS_ANNOT_SINGLE.matcher(line);
				if (matcher.find()) {
					return Stream.of(matcher.group("value"));
				}
			}
		}

		return Stream.of();
	}

	@Override
	public DependencyResolver updateDependencyResolver(DependencyResolver resolver) {
		resolver.addRepositories(getAllRepositories());
		resolver.addDependencies(getAllDependencies());
		return resolver;
	}

	public String getSuggestedMain() {
		if (!getResourceRef().isStdin()) {
			return getResourceRef().getFile().getName().replace(getMainExtension(), "");
		} else {
			return null;
		}
	}

	public static class KeyValue {
		final String key;
		final String value;

		public KeyValue(String key, String value) {
			this.key = key;
			this.value = value;
		}

		public String getKey() {
			return key;
		}

		public String getValue() {
			return value;
		}

		public String toManifestString() {
			return getKey() + ": " + value;
		}

		@Override
		public String toString() {
			return getKey() + "=" + getValue() == null ? "" : getValue();
		}
	}

	public List<KeyValue> getAllAgentOptions() {
		if (agentOptions == null) {
			agentOptions = collectAll(ScriptSource::collectAgentOptions);
		}
		return agentOptions;
	}

	private List<KeyValue> collectAgentOptions() {
		return collectRawOptions("JAVAAGENT")	.stream()
												.flatMap(ScriptSource::extractKeyValue)
												.map(ScriptSource::toKeyValue)
												.collect(Collectors.toCollection(ArrayList::new));
	}

	static Stream<String> extractKeyValue(String line) {
		return Arrays.stream(line.split(" +")).map(String::trim);
	}

	static public KeyValue toKeyValue(String line) {

		String[] split = line.split("=");
		String key;
		String value = null;

		if (split.length == 1) {
			key = split[0];
		} else if (split.length == 2) {
			key = split[0];
			value = split[1];
		} else {
			throw new IllegalStateException("Invalid key/value: " + line);
		}
		return new KeyValue(key, value);
	}

	public boolean isAgent() {
		return !getAllAgentOptions().isEmpty();
	}

	public List<MavenRepo> getAllRepositories() {
		if (repositories == null) {
			repositories = collectAll(ScriptSource::collectRepositories);
		}
		return repositories;
	}

	public List<MavenRepo> collectRepositories() {
		return getLines()	.stream()
							.filter(ScriptSource::isRepoDeclare)
							.flatMap(ScriptSource::extractRepositories)
							.map(replaceProperties)
							.map(DependencyUtil::toMavenRepo)
							.collect(Collectors.toCollection(ArrayList::new));
	}

	static boolean isRepoDeclare(String line) {
		return line.startsWith(REPOS_COMMENT_PREFIX) || line.contains(REPOS_ANNOT_PREFIX);
	}

	static Stream<String> extractRepositories(String line) {
		if (line.startsWith(REPOS_COMMENT_PREFIX)) {
			return Arrays.stream(line.split(" // ")[0].split("[ ;,]+")).skip(1).map(String::trim);
		}

		if (line.contains(REPOS_ANNOT_PREFIX)) {
			if (line.indexOf(REPOS_ANNOT_PREFIX) > line.indexOf("//")) {
				// ignore if on line that is a comment
				return Stream.of();
			}

			Map<String, String> args = new HashMap<>();

			Matcher matcher = REPOS_ANNOT_PAIRS.matcher(line);
			while (matcher.find()) {
				args.put(matcher.group("key"), matcher.group("value"));
			}
			if (!args.isEmpty()) {
				String repo = args.getOrDefault("name", args.get("root")) + "=" + args.get("root");
				return Stream.of(repo);
			} else {
				matcher = REPOS_ANNOT_SINGLE.matcher(line);
				if (matcher.find()) {
					return Stream.of(matcher.group("value"));
				}
			}
		}

		return Stream.of();
	}

	@Override
	public Optional<String> getDescription() {
		if (!description.isPresent()) {
			String desc = getLines().stream()
									.filter(ScriptSource::isDescriptionDeclare)
									.map(s -> s.substring(DESCRIPTION_COMMENT_PREFIX.length()))
									.collect(Collectors.joining("\n"));
			if (desc.isEmpty()) {
				description = Optional.empty();
			} else {
				description = Optional.of(desc);
			}
		}
		return description;
	}

	static boolean isDescriptionDeclare(String line) {
		return line.startsWith(DESCRIPTION_COMMENT_PREFIX);
	}

	@Override
	public Optional<String> getGav() {
		if (!gav.isPresent()) {
			List<String> gavs = getLines()	.stream()
											.filter(ScriptSource::isGavDeclare)
											.map(s -> s.substring(GAV_COMMENT_PREFIX.length()))
											.collect(Collectors.toList());
			if (gavs.isEmpty()) {
				gav = Optional.empty();
			} else {
				if (gavs.size() > 1) {
					Util.warnMsg(
							"Multiple //GAV lines found, only one should be defined in a source file. Using the first");
				}
				String maybeGav = DependencyUtil.gavWithVersion(gavs.get(0));
				if (!DependencyUtil.looksLikeAGav(maybeGav)) {
					throw new IllegalArgumentException(
							"//GAV line has wrong format, should be '//GAV groupid:artifactid[:version]'");
				}
				gav = Optional.of(gavs.get(0));
			}
		}
		return gav;
	}

	static boolean isGavDeclare(String line) {
		return line.startsWith(GAV_COMMENT_PREFIX);
	}

	protected List<String> collectOptions(String prefix) {
		List<String> options = collectRawOptions(prefix);

		// convert quoted content to list of strings as
		// just passing "--enable-preview --source 14" fails
		return Source.quotedStringToList(String.join(" ", options));
	}

	private List<String> collectRawOptions(String prefix) {
		// if (forJar())
		// return Collections.emptyList();

		String joptsPrefix = "//" + prefix;

		List<String> lines = getLines();

		List<String> javaOptions = lines.stream()
										.map(it -> it.split(" // ")[0]) // strip away nested comments.
										.filter(it -> it.startsWith(joptsPrefix + " ")
												|| it.startsWith(joptsPrefix + "\t") || it.equals(joptsPrefix))
										.map(it -> it.replaceFirst(joptsPrefix, "").trim())
										.collect(Collectors.toList());

		String envOptions = System.getenv("JBANG_" + prefix);
		if (envOptions != null) {
			javaOptions.add(envOptions);
		}
		return javaOptions;
	}

	@Override
	public List<String> getRuntimeOptions() {
		return collectOptions("JAVA_OPTIONS");
	}

	@Override
	public boolean enableCDS() {
		return !collectRawOptions("CDS").isEmpty();
	}

	@Override
	public String getJavaVersion() {
		Optional<String> version = collectAll(ScriptSource::collectJavaVersions).stream()
																				.filter(JavaUtil::checkRequestedVersion)
																				.max(new JavaUtil.RequestedVersionComparator());
		return version.orElse(null);
	}

	private List<String> collectJavaVersions() {
		return collectOptions("JAVA");
	}

	@Override
	public boolean isJShell() {
		return Source.isJShell(getResourceRef().getFile());
	}

	@Override
	public File getJarFile() {
		if (isJShell()) {
			return null;
		}
		if (jar == null) {
			File baseDir = Settings.getCacheDir(Cache.CacheClass.jars).toFile();
			File tmpJarDir = new File(baseDir, getResourceRef().getFile().getName() +
					"." + Util.getStableID(script));
			jar = new File(tmpJarDir.getParentFile(), tmpJarDir.getName() + ".jar");
		}
		return jar;
	}

	@Override
	public JarSource asJarSource() {
		if (jarSource == null) {
			File jarFile = getJarFile();
			if (jarFile != null && jarFile.exists()) {
				jarSource = JarSource.prepareJar(this);
			}
		}
		return jarSource;
	}

	@Override
	public ScriptSource asScriptSource() {
		return this;
	}

	protected static String getBackingFileContent(File backingFile) {
		try {
			return new String(Files.readAllBytes(backingFile.toPath()));
		} catch (IOException e) {
			throw new ExitException(BaseCommand.EXIT_UNEXPECTED_STATE,
					"Could not read script content for " + backingFile, e);
		}
	}

	public void copyFilesTo(Path dest) {
		List<RefTarget> files = getAllFiles();
		for (RefTarget file : files) {
			file.copy(dest);
		}
	}

	public List<RefTarget> getAllFiles() {
		if (filerefs == null) {
			filerefs = collectAll(ScriptSource::collectFiles);
		}
		return filerefs;
	}

	public List<RefTarget> collectFiles() {
		return getLines()	.stream()
							.filter(f -> f.startsWith(FILES_COMMENT_PREFIX))
							.flatMap(line -> Arrays	.stream(line.split(" // ")[0].split("[ ;,]+"))
													.skip(1)
													.map(String::trim))
							.map(replaceProperties)
							.map(this::toFileRef)
							.collect(Collectors.toCollection(ArrayList::new));
	}

	private RefTarget toFileRef(String fileReference) {
		return RefTarget.create(resourceRef.getOriginalResource(), fileReference);
	}

	public List<ScriptSource> getAllSources() {
		if (sources == null) {
			List<ScriptSource> scripts = new ArrayList<>();
			HashSet<ResourceRef> refs = new HashSet<>();
			// We should only return sources but we must avoid circular references via this
			// script, so we add this script's ref but not the script itself
			refs.add(resourceRef);
			collectAllSources(refs, scripts);
			sources = scripts;
		}
		return sources;
	}

	private void collectAllSources(Set<ResourceRef> refs, List<ScriptSource> scripts) {
		List<ScriptSource> srcs = collectSources();
		for (ScriptSource s : srcs) {
			if (!refs.contains(s.resourceRef)) {
				refs.add(s.resourceRef);
				scripts.add(s);
				s.collectAllSources(refs, scripts);
			}
		}
	}

	public List<ScriptSource> collectSources() {
		if (getLines() == null) {
			return Collections.emptyList();
		} else {
			String org = getResourceRef().getOriginalResource();
			Path baseDir = org != null ? getResourceRef().getFile().getAbsoluteFile().getParentFile().toPath()
					: Util.getCwd();
			return getLines()	.stream()
								.filter(f -> f.startsWith(SOURCES_COMMENT_PREFIX))
								.flatMap(line -> Arrays	.stream(line.split(" // ")[0].split("[ ;,]+"))
														.skip(1)
														.map(String::trim))
								.map(replaceProperties)
								.flatMap(line -> Util.explode(org, baseDir, line).stream())
								.map(this::getSibling)
								.collect(Collectors.toCollection(ArrayList::new));
		}
	}

	public ScriptSource getSibling(String resource) {
		ResourceRef siblingRef = resourceRef.asSibling(resource);
		return prepareScript(siblingRef, replaceProperties);
	}

	protected <R> List<R> collectAll(Function<ScriptSource, List<R>> func) {
		Stream<R> subs = getAllSources().stream().flatMap(s -> func.apply(s).stream());
		return Stream.concat(func.apply(this).stream(), subs).collect(Collectors.toList());
	}

	@Override
	public boolean isCreatedJar() {
		return getJarFile().exists();
	}

	public static ScriptSource prepareScript(String resource, Function<String, String> replaceProperties) {
		ResourceRef resourceRef = ResourceRef.forResource(resource);
		return prepareScript(resourceRef, replaceProperties);
	}

	public static ScriptSource prepareScript(ResourceRef resourceRef, Function<String, String> replaceProperties) {
		String originalResource = resourceRef.getOriginalResource();
		if (originalResource != null && originalResource.endsWith(".kt")) {
			return new KotlinScriptSource(resourceRef, replaceProperties);
		}
		if (originalResource != null && originalResource.endsWith(".md")) {
			return MarkdownScriptSource.create(resourceRef, replaceProperties);
		} else if (originalResource != null && originalResource.endsWith(".groovy")) {
			return new GroovyScriptSource(resourceRef, replaceProperties);
		} else {
			return new ScriptSource(resourceRef, replaceProperties);
		}
	}
}
