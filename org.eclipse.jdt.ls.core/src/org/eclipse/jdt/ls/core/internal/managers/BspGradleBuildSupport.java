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

import ch.epfl.scala.bsp4j.BuildServer;
import ch.epfl.scala.bsp4j.BuildTarget;
import ch.epfl.scala.bsp4j.DependencyModule;
import ch.epfl.scala.bsp4j.DependencyModulesParams;
import ch.epfl.scala.bsp4j.DependencyModulesResult;
import ch.epfl.scala.bsp4j.MavenDependencyModule;
import ch.epfl.scala.bsp4j.OutputPathsParams;
import ch.epfl.scala.bsp4j.OutputPathsResult;
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
			// TODO: refactor: map project to build target
			BuildTargetsManager.getInstance().setBuildTargets(project, buildTargets);
			updateClassPath(buildServer, project, monitor);
		}
	}

	public void updateClassPath(BuildServer buildServer, IProject project, IProgressMonitor monitor) throws JavaModelException {
		IJavaProject javaProject = JavaCore.create(project);
		List<IClasspathEntry> classpath = new LinkedList<>();

		Set<String> mainDependencies = new HashSet<>();
		Set<String> testDependencies = new HashSet<>();
		IClasspathAttribute testAttribute = JavaCore.newClasspathAttribute(IClasspathAttribute.TEST, "true");
		IClasspathAttribute optionalAttribute = JavaCore.newClasspathAttribute(IClasspathAttribute.OPTIONAL, "true");

		List<BuildTarget> buildTargets = BuildTargetsManager.getInstance().getBuildTargets(project);
		for (BuildTarget buildTarget : buildTargets) {
			boolean isTest = buildTarget.getTags().contains("test");

			OutputPathsResult outputResult = buildServer.buildTargetOutputPaths(new OutputPathsParams(Arrays.asList(buildTarget.getId()))).join();
			// TODO: assume only one output path?
			String outputUriString = outputResult.getItems().get(0).getOutputPaths().get(0).getUri();
			IPath outputPath = ResourceUtils.filePathFromURI(outputUriString);
			IPath relativeOutputPath = outputPath.makeRelativeTo(project.getLocation());
			IPath outputFullPath = project.getFolder(relativeOutputPath).getFullPath();

			SourcesResult sourcesResult = buildServer.buildTargetSources(new SourcesParams(Arrays.asList(buildTarget.getId()))).join();
			for (SourceItem source : sourcesResult.getItems().get(0).getSources()) {
				IPath sourcePath = ResourceUtils.filePathFromURI(source.getUri());
				IPath relativeSourcePath = sourcePath.makeRelativeTo(project.getLocation());
				IPath sourceFullPath = project.getFolder(relativeSourcePath).getFullPath();
				List<IClasspathAttribute> classpathAttributes = new LinkedList<>();
				if (isTest) {
					classpathAttributes.add(testAttribute);
				}
				if (source.getGenerated()) {
					classpathAttributes.add(optionalAttribute);
				}
				classpath.add(JavaCore.newSourceEntry(sourceFullPath, null, null, outputFullPath, classpathAttributes.toArray(new IClasspathAttribute[0])));
			}
			// TODO: handle resource?

			DependencyModulesResult dependencyModuleResult = buildServer.buildTargetDependencyModules(new DependencyModulesParams(Arrays.asList(buildTarget.getId()))).join();
			for (DependencyModule modules : dependencyModuleResult.getItems().get(0).getModules()) {
				MavenDependencyModule mavenModule = JSONUtility.toModel(modules.getData(), MavenDependencyModule.class);
				String uriString = mavenModule.getArtifacts().get(0).getUri();
				File file;
				try {
					file = new File(new URI(uriString));
				} catch (URISyntaxException e) {
					e.printStackTrace();
					return;
				}
				if (isTest) {
					testDependencies.add(file.getAbsolutePath());
				} else {
					mainDependencies.add(file.getAbsolutePath());
				}
			}
		}

		JvmBuildTargetExt jvmBuildTarget = JSONUtility.toModel(buildTargets.get(0).getData(), JvmBuildTargetExt.class);
		// TODO: the version might not be eclipse compatible
		// see: https://github.com/eclipse/buildship/blob/6727c8779029e86b0585e27784ca90b904b7ce35/org.eclipse.buildship.core/src/main/java/org/eclipse/buildship/core/internal/util/gradle/JavaVersionUtil.java#L24
		IVMInstall vm = EclipseVmUtil.findOrRegisterStandardVM(jvmBuildTarget.getTargetBytecodeVersion(), new File(jvmBuildTarget.getJavaHome()));
		classpath.add(JavaCore.newContainerEntry(JavaRuntime.newJREContainerPath(vm)));

		testDependencies = testDependencies.stream().filter(t -> {
			return !mainDependencies.contains(t);
		}).collect(Collectors.toSet());

		for (String mainDependency : mainDependencies) {
			classpath.add(JavaCore.newLibraryEntry(
				new org.eclipse.core.runtime.Path(mainDependency),
				null,
				null,
				ClasspathEntry.NO_ACCESS_RULES,
				ClasspathEntry.NO_EXTRA_ATTRIBUTES,
				false
			));
		}

		for (String testDependency : testDependencies) {
			classpath.add(JavaCore.newLibraryEntry(
				new org.eclipse.core.runtime.Path(testDependency),
				null,
				null,
				ClasspathEntry.NO_ACCESS_RULES,
				new IClasspathAttribute[]{ testAttribute },
				true
			));
		}
		javaProject.setRawClasspath(classpath.toArray(IClasspathEntry[]::new), javaProject.getOutputLocation(), monitor);
	}

	@Override
	public boolean isBuildFile(IResource resource) {
		if (resource != null && resource.getType() == IResource.FILE && isBuildLikeFileName(resource.getName())
			&& ProjectUtils.isGradleProject(resource.getProject())) {
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
}
