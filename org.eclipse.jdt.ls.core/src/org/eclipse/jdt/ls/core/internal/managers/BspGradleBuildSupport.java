/*******************************************************************************
 * Copyright (c) 2016-2022 Red Hat Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Red Hat Inc. - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.ls.core.internal.managers;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.eclipse.bsp4j.extended.JvmBuildTargetExt;
import org.eclipse.buildship.core.internal.workspace.EclipseVmUtil;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IClasspathAttribute;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.internal.core.ClasspathEntry;
import org.eclipse.jdt.launching.IVMInstall;
import org.eclipse.jdt.launching.JavaRuntime;
import org.eclipse.jdt.ls.core.internal.JSONUtility;
import org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin;
import org.eclipse.jdt.ls.core.internal.ProjectUtils;
import org.eclipse.jdt.ls.core.internal.ResourceUtils;
import org.eclipse.jdt.ls.core.internal.managers.ProjectsManager.CHANGE_TYPE;

import ch.epfl.scala.bsp4j.BuildServer;
import ch.epfl.scala.bsp4j.BuildTarget;
import ch.epfl.scala.bsp4j.BuildTargetIdentifier;
import ch.epfl.scala.bsp4j.DependencyModule;
import ch.epfl.scala.bsp4j.DependencyModulesParams;
import ch.epfl.scala.bsp4j.DependencyModulesResult;
import ch.epfl.scala.bsp4j.MavenDependencyModule;
import ch.epfl.scala.bsp4j.MavenDependencyModuleArtifact;
import ch.epfl.scala.bsp4j.OutputPathItem;
import ch.epfl.scala.bsp4j.OutputPathsParams;
import ch.epfl.scala.bsp4j.OutputPathsResult;
import ch.epfl.scala.bsp4j.ResourcesParams;
import ch.epfl.scala.bsp4j.ResourcesResult;
import ch.epfl.scala.bsp4j.SourceItem;
import ch.epfl.scala.bsp4j.SourcesParams;
import ch.epfl.scala.bsp4j.SourcesResult;
import ch.epfl.scala.bsp4j.WorkspaceBuildTargetsResult;

/**
 * @author Fred Bricon
 *
 */
public class BspGradleBuildSupport implements IBuildSupport {

	public static final Pattern GRADLE_FILE_EXT = Pattern.compile("^.*\\.gradle(\\.kts)?$");
	public static final String GRADLE_PROPERTIES = "gradle.properties";

	private static final IClasspathAttribute testAttribute = JavaCore.newClasspathAttribute(IClasspathAttribute.TEST, "true");
	private static final IClasspathAttribute optionalAttribute = JavaCore.newClasspathAttribute(IClasspathAttribute.OPTIONAL, "true");

	@Override
	public boolean applies(IProject project) {
		return ProjectUtils.hasNature(project, BspGradleProjectNature.NATURE_ID);
	}

	@Override
	public void update(IProject project, boolean force, IProgressMonitor monitor) throws CoreException {
		if (!applies(project)) {
			return;
		}
		JavaLanguageServerPlugin.logInfo("Starting Gradle update for " + project.getName());

		File buildFile = project.getFile(GradleProjectImporter.BUILD_GRADLE_DESCRIPTOR).getLocation().toFile();
		File settingsFile = project.getFile(GradleProjectImporter.SETTINGS_GRADLE_DESCRIPTOR).getLocation().toFile();
		File buildKtsFile = project.getFile(GradleProjectImporter.BUILD_GRADLE_KTS_DESCRIPTOR).getLocation().toFile();
		File settingsKtsFile = project.getFile(GradleProjectImporter.SETTINGS_GRADLE_KTS_DESCRIPTOR).getLocation().toFile();
		boolean shouldUpdate = force || (buildFile.exists() && JavaLanguageServerPlugin.getDigestStore().updateDigest(buildFile.toPath()))
				|| (settingsFile.exists() && JavaLanguageServerPlugin.getDigestStore().updateDigest(settingsFile.toPath()))
				|| (buildKtsFile.exists() && JavaLanguageServerPlugin.getDigestStore().updateDigest(buildKtsFile.toPath()))
				|| (settingsKtsFile.exists() && JavaLanguageServerPlugin.getDigestStore().updateDigest(settingsKtsFile.toPath()));
		if (shouldUpdate) {
			BuildServer buildServer = JavaLanguageServerPlugin.getBuildServer();
			if (buildServer == null) {
				return;
			}
			buildServer.workspaceReload().join();
			WorkspaceBuildTargetsResult workspaceBuildTargetsResult = buildServer.workspaceBuildTargets().join();
			List<BuildTarget> buildTargets = workspaceBuildTargetsResult.getTargets();
			BuildTargetsManager.getInstance().setBuildTargets(project, buildTargets);
			updateClassPath(project, monitor);
		}
	}

	public void updateClassPath(IProject project, IProgressMonitor monitor) throws JavaModelException {
		BuildServer buildServer = JavaLanguageServerPlugin.getBuildServer();
		if (buildServer == null) {
			return;
		}

		IJavaProject javaProject = JavaCore.create(project);
		List<IClasspathEntry> classpath = new LinkedList<>();

		Set<MavenDependencyModule> mainDependencies = new HashSet<>();
		Set<MavenDependencyModule> testDependencies = new HashSet<>();

		List<BuildTarget> buildTargets = BuildTargetsManager.getInstance().getBuildTargets(project);
		Set<BuildTargetIdentifier> projectDependencies = new HashSet<>();
		for (BuildTarget buildTarget : buildTargets) {
			boolean isTest = buildTarget.getTags().contains("test");

			OutputPathsResult outputResult = buildServer.buildTargetOutputPaths(new OutputPathsParams(Arrays.asList(buildTarget.getId()))).join();
			List<OutputPathItem> outputPaths = outputResult.getItems().get(0).getOutputPaths();
			String sourceOutputUriString = outputPaths.get(0).getUri();
			IPath sourceOutputPath = ResourceUtils.filePathFromURI(sourceOutputUriString);
			IPath relativeSourceOutputPath = sourceOutputPath.makeRelativeTo(project.getLocation());
			IPath sourceOutputFullPath = project.getFolder(relativeSourceOutputPath).getFullPath();

			SourcesResult sourcesResult = buildServer.buildTargetSources(new SourcesParams(Arrays.asList(buildTarget.getId()))).join();
			for (SourceItem source : sourcesResult.getItems().get(0).getSources()) {
				IPath sourcePath = ResourceUtils.filePathFromURI(source.getUri());
				if (!sourcePath.toFile().exists()) {
					continue;
				}
				IPath relativeSourcePath = sourcePath.makeRelativeTo(project.getLocation());
				IPath sourceFullPath = project.getFolder(relativeSourcePath).getFullPath();
				List<IClasspathAttribute> classpathAttributes = new LinkedList<>();
				if (isTest) {
					classpathAttributes.add(testAttribute);
				}
				if (source.getGenerated()) {
					classpathAttributes.add(optionalAttribute);
				}
				classpath.add(JavaCore.newSourceEntry(sourceFullPath, null, null, sourceOutputFullPath, classpathAttributes.toArray(new IClasspathAttribute[0])));
			}

			if (outputPaths.size() > 1) {
				// handle resource output
				String resourceOutputUriString = outputResult.getItems().get(0).getOutputPaths().get(1).getUri();
				IPath resourceOutputPath = ResourceUtils.filePathFromURI(resourceOutputUriString);
				IPath relativeResourceOutputPath = resourceOutputPath.makeRelativeTo(project.getLocation());
				IPath resourceOutputFullPath = project.getFolder(relativeResourceOutputPath).getFullPath();

				ResourcesResult resourcesResult = buildServer.buildTargetResources(new ResourcesParams(Arrays.asList(buildTarget.getId()))).join();
				for (String resourceUri : resourcesResult.getItems().get(0).getResources()) {
					IPath resourcePath = ResourceUtils.filePathFromURI(resourceUri);
					if (!resourcePath.toFile().exists()) {
						continue;
					}
					IPath relativeResourcePath = resourcePath.makeRelativeTo(project.getLocation());
					IPath resourceFullPath = project.getFolder(relativeResourcePath).getFullPath();
					List<IClasspathAttribute> classpathAttributes = new LinkedList<>();
					if (isTest) {
						classpathAttributes.add(testAttribute);
					}
					classpathAttributes.add(optionalAttribute);
					classpath.add(JavaCore.newSourceEntry(resourceFullPath, null, null, resourceOutputFullPath, classpathAttributes.toArray(new IClasspathAttribute[0])));
				}
			}

			DependencyModulesResult dependencyModuleResult = buildServer.buildTargetDependencyModules(new DependencyModulesParams(Arrays.asList(buildTarget.getId()))).join();
			for (DependencyModule module : dependencyModuleResult.getItems().get(0).getModules()) {
				MavenDependencyModule mavenModule = JSONUtility.toModel(module.getData(), MavenDependencyModule.class);
				if (isTest) {
					testDependencies.add(mavenModule);
				} else {
					mainDependencies.add(mavenModule);
				}
			}

			projectDependencies.addAll(buildTarget.getDependencies());
		}

		JvmBuildTargetExt jvmBuildTarget = JSONUtility.toModel(buildTargets.get(0).getData(), JvmBuildTargetExt.class);
		String javaVersion = getEclipseCompatibleVersion(jvmBuildTarget.getTargetBytecodeVersion());
		IVMInstall vm = EclipseVmUtil.findOrRegisterStandardVM(javaVersion, new File(jvmBuildTarget.getJavaHome()));
		classpath.add(JavaCore.newContainerEntry(JavaRuntime.newJREContainerPath(vm)));

		testDependencies = testDependencies.stream().filter(t -> {
			return !mainDependencies.contains(t);
		}).collect(Collectors.toSet());

		addProjectDependenciesToClasspath(classpath, projectDependencies);
		addModeDependenciesToClasspath(classpath, mainDependencies, false);
		addModeDependenciesToClasspath(classpath, testDependencies, true);

		javaProject.setRawClasspath(classpath.toArray(IClasspathEntry[]::new), javaProject.getOutputLocation(), monitor);
	}

	@Override
	public boolean fileChanged(IResource resource, CHANGE_TYPE changeType, IProgressMonitor monitor) throws CoreException {
		if (resource == null || !applies(resource.getProject())) {
			return false;
		}
		return IBuildSupport.super.fileChanged(resource, changeType, monitor) || isBuildFile(resource);
	}

	@Override
	public boolean isBuildFile(IResource resource) {
		if (resource != null && resource.getType() == IResource.FILE && isBuildLikeFileName(resource.getName())
			&& ProjectUtils.hasNature(resource.getProject(), BspGradleProjectNature.NATURE_ID)) {
			try {
				if (!ProjectUtils.isJavaProject(resource.getProject())) {
					return true;
				}
				IJavaProject javaProject = JavaCore.create(resource.getProject());
				IPath outputLocation = javaProject.getOutputLocation();
				return outputLocation == null || !outputLocation.isPrefixOf(resource.getFullPath());
			} catch (JavaModelException e) {
				JavaLanguageServerPlugin.logException(e.getMessage(), e);
			}
		}
		return false;
	}

	@Override
	public boolean isBuildLikeFileName(String fileName) {
		return GRADLE_FILE_EXT.matcher(fileName).matches() || fileName.equals(GRADLE_PROPERTIES);
	}

	/**
	 * Get the Eclipse compatible Java version string.
	 * <pre>
	 * See: <a href="https://github.com/eclipse/buildship/blob/6727c8779029e86b0585e27784ca90b904b7ce35/
	   org.eclipse.buildship.core/src/main/java/org/eclipse/buildship/core/internal/util/gradle/JavaVersionUtil.java#L24">
	   org.eclipse.buildship.core.internal.util.gradle.JavaVersionUtil</a>
	 * </pre>
	 */
	String getEclipseCompatibleVersion(String javaVersion) {
		if ("1.9".equals(javaVersion)) {
			return "9";
		} else if ("1.10".equals(javaVersion)) {
			return "10";
		}

		return javaVersion;
	}

	private void addProjectDependenciesToClasspath(List<IClasspathEntry> classpath, Set<BuildTargetIdentifier> projectDependencies) {
		for (BuildTargetIdentifier dependency : projectDependencies) {
			String uriString = dependency.getUri();
			URI uri = null;
			// TODO: extract to util
			try {
				uri = new URI(uriString);
				uri = new URI(uri.getScheme(), uri.getHost(), uri.getPath(), null, uri.getFragment());
			} catch (URISyntaxException e) {
				e.printStackTrace();
			}
			if (uri != null) {
				IProject dependencyProject = ProjectUtils.getProjectFromUri(uri.toString());
				if (dependencyProject != null) {
					classpath.add(JavaCore.newProjectEntry(dependencyProject.getFullPath()));
				}
			}
		}
	}

	private void addModeDependenciesToClasspath(List<IClasspathEntry> classpath, Set<MavenDependencyModule> modules, boolean isTest) {
		for (MavenDependencyModule mainDependency : modules) {
			File artifact = null;
			File sourceArtifact = null;
			for (MavenDependencyModuleArtifact a : mainDependency.getArtifacts()) {
				try {
					if (a.getClassifier() == null) {
							artifact = new File(new URI(a.getUri()));
					} else if ("sources".equals(a.getClassifier())) {
						sourceArtifact = new File(new URI(a.getUri()));
					}
				} catch (URISyntaxException e) {
					e.printStackTrace();
				}
			}
			classpath.add(JavaCore.newLibraryEntry(
				new org.eclipse.core.runtime.Path(artifact.getAbsolutePath()),
				sourceArtifact == null ? null : new org.eclipse.core.runtime.Path(sourceArtifact.getAbsolutePath()),
				null,
				ClasspathEntry.NO_ACCESS_RULES,
				isTest ? new IClasspathAttribute[]{ testAttribute } : ClasspathEntry.NO_EXTRA_ATTRIBUTES,
				false
			));
		}
	}
}
