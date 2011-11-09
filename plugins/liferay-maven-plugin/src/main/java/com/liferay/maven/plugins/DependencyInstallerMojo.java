/**
 * Copyright (c) 2000-2011 Liferay, Inc. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */

package com.liferay.maven.plugins;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.installer.ArtifactInstallationException;
import org.apache.maven.artifact.installer.ArtifactInstaller;
import org.apache.maven.artifact.metadata.ArtifactMetadata;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.DefaultArtifactRepository;
import org.apache.maven.artifact.repository.layout.ArtifactRepositoryLayout;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.License;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.artifact.ProjectArtifactMetadata;
import org.apache.maven.project.validation.ModelValidationResult;
import org.apache.maven.project.validation.ModelValidator;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.WriterFactory;

import com.liferay.portal.kernel.util.FileUtil;
import com.liferay.portal.kernel.util.PropsKeys;
import com.liferay.portal.kernel.util.StringUtil;
import com.liferay.portal.kernel.xml.Document;
import com.liferay.portal.kernel.xml.Element;
import com.liferay.portal.kernel.xml.SAXReaderUtil;
import com.liferay.portal.util.InitUtil;
import com.liferay.portal.util.PropsUtil;

/**
 * Installs 3rd-party libraries based on {@link #sourceVersionFile} located in 
 * {@link #libDirPath} into {@link #customLocalRepositoryPath} or default local 
 * maven repository. Only libraries that match regular expressions specified
 * in {@link #include} element are used. Pom definition with corresponding 
 * dependencies is generated into {@link #generatedPomLocation}
 * 
 * @author Jakub Liska
 * @goal install-dependencies
 */
public class DependencyInstallerMojo extends AbstractMojo {
	
	public void execute() throws MojoExecutionException {

		try {
			if (customLocalRepositoryPath != null) {
				URL repositoryPath = customLocalRepositoryPath.toURI().toURL();

				localRepository = new DefaultArtifactRepository(
						localRepositoryId, repositoryPath.toString(),
						repositoryLayouts.get("default"));
			}

			initClassLoader();

			initSpring();

			doExecute();
		}
		catch (Exception e) {
			throw new MojoExecutionException(e.getMessage(), e);
		}
	}

	private void doExecute() throws Exception {
		int installed = 0;
		
		File versionFile = new File(libDirPath, sourceVersionFile);

		if (!versionFile.exists()) {
			throw new IllegalStateException(versionFile + " doesn't exist");
		}

		Map<String, Pattern[]> inclusionPatterns = new HashMap<String, Pattern[]>();
		Map<String, Dependency> sortedDeps = new TreeMap<String, Dependency>();

		addInclusionPatterns(inclusionPatterns);

		String content = FileUtil.read(versionFile);

		getLog().info("Parsing : " + versionFile);

		Document document = SAXReaderUtil.read(content);

		Element rootElement = document.getRootElement();

		for (Element libElmnt : rootElement.elements()) {
			String elementName = libElmnt.getName();

			if ("library".equals(elementName)) {
				libElmnt.detach();

				String fullFileName = libElmnt.elementText("file-name");
				String projectName = libElmnt.elementText("project-name");
				
				if (fullFileName == null || fullFileName.isEmpty()) {
					throw new IllegalStateException(
						"file-name of '" + projectName + "' must not be empty");
				}
				
				String fileName = StringUtil.extractLast(fullFileName, "/");
				String subDir = StringUtil.extractFirst(fullFileName, "/");

				Pattern[] patterns = inclusionPatterns.get(subDir);
				
				if (patterns == null || !matches(fileName, patterns)) {
					continue;
				}
				
				String projectUrl = libElmnt.elementText("project-url");
				String version = libElmnt.elementText("version");

				if (version == null || version.isEmpty()) {
					getLog().warn("Version of: " + fullFileName + " is missing");
					getLog().warn("Setting version to 1.0");
					version = "1.0";
				}

				List<License> licenses = new ArrayList<License>();

				Element licensesElem = libElmnt.element("licenses");

				if (licensesElem != null) {
					for (Element licenseElem : licensesElem.elements()) {
						getLicenses(licenses, licenseElem);
					}
				}
				else {
					getLog().warn(
						"Library: " + fullFileName + " has no license");
				}

				String artifactId = StringUtil.extractFirst(fileName, ".");
				String packaging = StringUtil.extractLast(fileName, ".");
				String groupId = projGroupId + "." + subDir;
				String subDirPath = libDirPath + File.separator + subDir;
				String filePath = subDirPath + File.separator + fileName;

				File targetFile = new File(filePath);
				
				if (!targetFile.exists()) {
					throw new IllegalStateException(
						"File: " + filePath + " not found");
				}

				Model model = createModel(
						artifactId, groupId, packaging, projectName,
						projectUrl, version, licenses, null);

				validate(model);

				Dependency dep = createDependency(
							artifactId, groupId, packaging, version);

				sortedDeps.put(groupId + "." + fileName, dep);

				Artifact artifact = null;
				File pomFile = null;
				
				try {
					pomFile = generatePomFile(model, null);

					artifact = createArtifact(
							artifactId, groupId, packaging, pomFile, version);

					installer.install(targetFile, artifact, localRepository);
					
					installed++;
				}
				catch (ArtifactInstallationException aie) {
					throw new Exception(
						"Error installing artifact '" +
						artifact.getDependencyConflictId() + "': " +
						aie.getMessage(), aie);
				} catch (MojoExecutionException mex) {
					throw new Exception(mex.getMessage(), mex);
				}
				finally {
					if (pomFile != null) {
						pomFile.delete();
					}
				}
			}
			else {
				throw new IllegalStateException("Not suitable xml definition");
			}
		}

		if (sortedDeps.size() > 0) {
			List<Dependency> deps = new ArrayList<Dependency>();

			for (Map.Entry<String, Dependency> e : sortedDeps.entrySet()) {
				deps.add(e.getValue());
			}

			int depCount = deps.size();
			
			Model targetModel = createModel(
					projArtifactId, projGroupId, "pom", generatedPomName,
					null, generatedPomVersion, null, deps);

			getLog().info("Validating resulting pom model \n");

			validate(targetModel);

			getLog().info("Generating : " + generatedPomLocation);

			File pom = generatePomFile(targetModel, generatedPomLocation);

			System.out.println("\n\n");
			getLog().info(installed + " artifacts installed");
			getLog().info(
				"Generated pom file: '" + pom + 
				"' with " + depCount + " dependencies \n");
			
			if (depCount != installed) {
				throw new IllegalAccessException(
					"Dependency count: " + depCount + 
					" differs from number of installations: " + installed);
			}
		}
	}

	private void addInclusionPatterns(Map<String, Pattern[]> inclusionPatterns) {
		if (include != null && !include.isEmpty()) {
			for (String key : include.keySet()) {
				String regexp = include.get(key);
				if (regexp != null && !regexp.isEmpty()) {
					String[] regexps = regexp.trim().split(",");
					Pattern[] patterns = new Pattern[regexps.length];
					for (int i = 0; i < regexps.length; i++) {
						
						/** precompiling to discover invalid regexps */
						
						patterns[i] = Pattern.compile(regexps[i].trim());
					}
					inclusionPatterns.put(key, patterns);
				}
			}
			
			if (inclusionPatterns.isEmpty()) {
				throw new IllegalStateException(
					"\n\n Specify inclusion regexp for sub-directories");
			}
		} else {
			throw new IllegalStateException("\n\n Specify sub-directories");
		}
	}

	private Artifact createArtifact(
		String artifactId, String groupId, String packaging, File pomFile,
		String version) {

		Artifact artifact = artifactFactory.createBuildArtifact(
				groupId, artifactId, version, packaging);

		String path = localRepository.pathOf(artifact);
		
		File repoFile = new File(localRepository.getBasedir(), path);

		if (repoFile.exists()) {
			File artifactDir = repoFile.getParentFile().getParentFile();
			if (artifactDir.getName().equals(artifactId)) {
				FileUtil.deltree(artifactDir);
			}
			else {
				throw new IllegalStateException(
					"Name of artifact: '" + artifactId +
					"' differs from artifact directory: " + artifactDir);
			}
		}

		ArtifactMetadata pomMetadata =
			new ProjectArtifactMetadata(artifact, pomFile);

		artifact.addMetadata(pomMetadata);
		artifact.setRelease(true);

		return artifact;
	}

	private Dependency createDependency(
		String artifactId, String groupId, String packaging, String version) {

		Dependency dep = new Dependency();

		dep.setArtifactId(artifactId);
		dep.setGroupId(groupId);
		dep.setScope(dependencyScope);
		dep.setType(packaging);
		dep.setVersion(version);

		return dep;
	}

	private Model createModel(
		String artifactId, String groupId, String packaging, String name,
		String url, String version, List<License> licenses,
		List<Dependency> deps) {

		Model model = new Model();

		model.setArtifactId(artifactId);
		model.setDependencies(deps);
		model.setDescription("POM generated by liferay-maven-plugin");
		model.setGroupId(groupId);
		model.setLicenses(licenses);
		model.setModelVersion("4.0.0");
		model.setName(name);
		model.setPackaging(packaging);
		model.setUrl(url);
		model.setVersion(version);

		return model;
	}

	private File generatePomFile(Model model, String destination)
		throws MojoExecutionException {

		Writer writer = null;
		File pomFile = null;
		
		try {
			if (destination == null) {
				pomFile = File.createTempFile("mvninstall", ".pom");
			}
			else {
				pomFile = new File(destination);
			}
			writer = WriterFactory.newXmlWriter(pomFile);
			new MavenXpp3Writer().write(writer, model);

			return pomFile;
		}
		catch (IOException e) {
			throw new MojoExecutionException(
				"Error writing temporary POM file: " + e.getMessage(), e);
		}
		finally {
			IOUtil.close(writer);
		}
	}

	private void getLicenses(List<License> licenses, Element licenseElem) {
		License license = new License();

		String licenceName = licenseElem.elementText("license-name");
		String copyRight = licenseElem.elementText("copyright-notice");

		license.setName(licenceName);
		license.setUrl(copyRight);
		licenses.add(license);
	}

	private void initClassLoader() throws Exception {

		synchronized (DependencyInstallerMojo.class) {
			Class<?> clazz = getClass();

			URLClassLoader classLoader =
				(URLClassLoader) clazz.getClassLoader();

			Method method = URLClassLoader.class.getDeclaredMethod(
				"addURL", URL.class);

			method.setAccessible(true);

			for (Object object : project.getCompileClasspathElements()) {
				String path = (String) object;

				File file = new File(path);

				URI uri = file.toURI();

				method.invoke(classLoader, uri.toURL());
			}
		}
	}

	private void initSpring() {

		/** SpringBuilderMojo uses the same utils as this one */

		PropsUtil.set("spring.configs", "META-INF/service-builder-spring.xml");
		PropsUtil.set(
			PropsKeys.RESOURCE_ACTIONS_READ_PORTLET_RESOURCES, "false");

		InitUtil.initWithSpring();
	}

	private boolean matches(String input, Pattern[] pattern) {
		for (int i = 0; i < pattern.length; i++) {
			if (pattern[i].matcher(input).matches()) {
				return true;
			}
		}
		return false;
	}
	
	private void validate(Model model) throws MojoExecutionException {
		ModelValidationResult result = modelValidator.validate(model);

		if (result.getMessageCount() > 0) {
			throw new MojoExecutionException(
				"The artifact information is incomplete or not valid:\n" +
					result.render("  "));
		}
	}

	/**
	 * @component
	 */
	private ArtifactFactory artifactFactory;

	/**
	 * Destination where the resulting pom will be generated to
	 * 
	 * @parameter default-value="${basedir}/target/generated-pom.xml"
	 * @required
	 */
	private String generatedPomLocation;

	/**
	 * @parameter default-value="Liferay dependencies"
	 * @required
	 */
	private String generatedPomName;

	/**
	 * @parameter default-value="${project.version}"
	 * @required
	 */
	private String generatedPomVersion;

	/**
	 * Inclusion patterns for files in sub-directories.
	 * Use java regular expressions separated by colons
	 * <pre>
	 * {@code
	 * <include>
	 *    <development>jsf-.*,derby,catalina,ant-.*,jalopy</development>
	 *    <global>.*</global>
	 *    <portal>chemistry-.*,commons-.*,jackrabbit-.*,spring-.*</portal>
	 * </include>
	 * }
	 * </pre>
	 * 
	 * @parameter
	 * @required
	 */
	private Map<String, String> include;

	/**
	 * @component
	 */
	private ArtifactInstaller installer;

	/**
	 * Directory containing {@link #sourceVersionFile} and sub-dirs with files
	 * 
	 * @parameter
	 * @required
	 */
	private File libDirPath;

	/**
	 * @parameter expression="${localRepository}"
	 * @required
	 * @readonly
	 */
	private ArtifactRepository localRepository;

	/**
	 * @parameter default-value="liferay-third-party-deps"
	 */
	private String localRepositoryId;

	/**
	 * The path to a custom local repository directory.
	 * If not specified, Maven settings localRepositoryPath will be used
	 * 
	 * @parameter expression="${localRepositoryPath}"
	 */
	private File customLocalRepositoryPath;

	/**
	 * @component
	 */
	private ModelValidator modelValidator;

	/**
	 * @parameter expression="${project}"
	 * @required
	 * @readonly
	 */
	private MavenProject project;

	/**
	 * artifactId of pom definition that is to be generated
	 * 
	 * @parameter default-value="${project.artifactId}"
	 * @required
	 */
	private String projArtifactId;

	/**
	 * groupId of pom definition that is to be generated
	 * 
	 * @parameter default-value="${project.groupId}"
	 * @required
	 */
	private String projGroupId;

	/**
	 * @component role=
	 *            "org.apache.maven.artifact.repository.layout.ArtifactRepositoryLayout"
	 */
	private Map<String, ArtifactRepositoryLayout> repositoryLayouts;

	/**
	 * Scope of all dependencies in generated pom definition
	 * 
	 * @parameter default-value="test"
	 * @required
	 */
	private String dependencyScope;

	/**
	 * Notice that sub-directory in file-name is mandatory
	 * 
	 * <pre>
	 * {@code
	 * <libraries>
	 *  <library>
	 * 	  <file-name>sub-directory/dependency.jar</file-name>
	 *	  <version>1.1</version>
	 *	  <project-name>Dependency Name</project-name>
	 *	  <project-url>http://www.example.com</project-url>
	 * 	  <licenses>
	 *		<license>
	 *			<license-name>Licence Name version 1.1</license-name>
	 *          <copyright-notice>Copyright (c)</copyright-notice>
	 *		</license>
	 *	  </licenses>
	 *   </library>
	 * </libraries>
	 * }
	 * </pre>
	 * 
	 * @parameter default-value="versions.xml"
	 */
	private String sourceVersionFile;
	
}
