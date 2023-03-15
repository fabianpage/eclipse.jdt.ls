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
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.core.resources.ICommand;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.ls.core.internal.AbstractProjectImporter;
import org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin;
import org.eclipse.jdt.ls.core.internal.ProjectUtils;
import org.eclipse.jdt.ls.core.internal.preferences.Preferences;

import ch.epfl.scala.bsp4j.BuildServer;
import ch.epfl.scala.bsp4j.BuildTarget;
import ch.epfl.scala.bsp4j.WorkspaceBuildTargetsResult;

/**
 * @author Fred Bricon
 *
 */
@SuppressWarnings("restriction")
public class BspGradleProjectImporter extends AbstractProjectImporter {

	public static final String BUILD_GRADLE_DESCRIPTOR = "build.gradle";
	public static final String BUILD_GRADLE_KTS_DESCRIPTOR = "build.gradle.kts";
	public static final String SETTINGS_GRADLE_DESCRIPTOR = "settings.gradle";
	public static final String SETTINGS_GRADLE_KTS_DESCRIPTOR = "settings.gradle.kts";

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.ls.core.internal.managers.IProjectImporter#applies(org.eclipse.core.runtime.IProgressMonitor)
	 */
	@Override
	public boolean applies(IProgressMonitor monitor) throws CoreException {
		if (rootFolder == null) {
			return false;
		}
		Preferences preferences = getPreferences();
		if (!preferences.isImportGradleEnabled()) {
			return false;
		}
		if (directories == null) {
			BasicFileDetector gradleDetector = new BasicFileDetector(rootFolder.toPath(), BUILD_GRADLE_DESCRIPTOR,
					SETTINGS_GRADLE_DESCRIPTOR, BUILD_GRADLE_KTS_DESCRIPTOR, SETTINGS_GRADLE_KTS_DESCRIPTOR)
					.includeNested(false)
					.addExclusions("**/build")//default gradle build dir
					.addExclusions("**/bin");
			for (IProject project : ProjectUtils.getAllProjects()) {
				if (!ProjectUtils.isGradleProject(project)) {
					String path = project.getLocation().toOSString();
					gradleDetector.addExclusions(path);
				}
			}
			directories = gradleDetector.scan(monitor);
		}
		return !directories.isEmpty();
	}

	@Override
	public boolean applies(Collection<IPath> buildFiles, IProgressMonitor monitor) {
		if (!getPreferences().isImportGradleEnabled()) {
			return false;
		}

		Collection<Path> configurationDirs = findProjectPathByConfigurationName(buildFiles, Arrays.asList(
			BUILD_GRADLE_DESCRIPTOR,
			SETTINGS_GRADLE_DESCRIPTOR,
			BUILD_GRADLE_KTS_DESCRIPTOR,
			SETTINGS_GRADLE_KTS_DESCRIPTOR
		), false /*includeNested*/);
		if (configurationDirs == null || configurationDirs.isEmpty()) {
			return false;
		}

		Set<Path> noneGradleProjectPaths = new HashSet<>();
		for (IProject project : ProjectUtils.getAllProjects()) {
			if (!ProjectUtils.isGradleProject(project)) {
				noneGradleProjectPaths.add(project.getLocation().toFile().toPath());
			}
		}

		this.directories = configurationDirs.stream()
			.filter(d -> {
				boolean folderIsImported = noneGradleProjectPaths.stream().anyMatch(path -> {
					return path.compareTo(d) == 0;
				});
				return !folderIsImported;
			})
			.collect(Collectors.toList());

		return !this.directories.isEmpty();
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.ls.core.internal.managers.IProjectImporter#importToWorkspace(org.eclipse.core.runtime.IProgressMonitor)
	 */
	@Override
	public void importToWorkspace(IProgressMonitor monitor) throws CoreException {
		BuildServer buildServer = JavaLanguageServerPlugin.getBuildServer();
		if (buildServer == null) {
			return;
		}
		WorkspaceBuildTargetsResult workspaceBuildTargetsResult = buildServer.workspaceBuildTargets().join();
		List<BuildTarget> buildTargets = workspaceBuildTargetsResult.getTargets();

		IProject project = createProjectIfNotExist(buildTargets, monitor);
		if (project == null) {
			return;
		}

		BuildTargetsManager.getInstance().setBuildTargets(project, buildTargets);

		// store the digest for the imported gradle projects.
		ProjectUtils.getGradleProjects().forEach(p -> {
			File buildFile = p.getFile(BUILD_GRADLE_DESCRIPTOR).getLocation().toFile();
			File settingsFile = p.getFile(SETTINGS_GRADLE_DESCRIPTOR).getLocation().toFile();
			File buildKtsFile = p.getFile(BUILD_GRADLE_KTS_DESCRIPTOR).getLocation().toFile();
			File settingsKtsFile = p.getFile(SETTINGS_GRADLE_KTS_DESCRIPTOR).getLocation().toFile();
			try {
				if (buildFile.exists()) {
					JavaLanguageServerPlugin.getDigestStore().updateDigest(buildFile.toPath());
				} else if (buildKtsFile.exists()) {
					JavaLanguageServerPlugin.getDigestStore().updateDigest(buildKtsFile.toPath());
				}
				if (settingsFile.exists()) {
					JavaLanguageServerPlugin.getDigestStore().updateDigest(settingsFile.toPath());
				} else if (settingsKtsFile.exists()) {
					JavaLanguageServerPlugin.getDigestStore().updateDigest(settingsKtsFile.toPath());
				}
			} catch (CoreException e) {
				JavaLanguageServerPlugin.logException("Failed to update digest for gradle build file", e);
			}
		});

		BspGradleBuildSupport bs = new BspGradleBuildSupport();
		bs.updateClassPath(buildServer, project, monitor);
	}

	private IProject createProjectIfNotExist(List<BuildTarget> buildTargets, IProgressMonitor monitor) throws CoreException {
		// TODO: build targets may have multiple projects, need to distinguish them.
		File projectDirectory;
		try {
			URI uri = new URI(buildTargets.get(0).getId().getUri());
			URI normalizedUri = new URI(uri.getScheme(),
				uri.getAuthority(),
				uri.getPath(),
				null, // Ignore the query part of the input url
				uri.getFragment());
			projectDirectory = new File(normalizedUri);
		} catch (URISyntaxException e) {
			// TODO: handle exception
			return null;
		}
		IProject[] allProjects = ProjectUtils.getAllProjects();
		Optional<IProject> projectOrNull = Arrays.stream(allProjects).filter(p -> {
			File loc = p.getLocation().toFile();
			return loc.equals(projectDirectory);
		}).findFirst();

		IProject project;
		if (projectOrNull.isPresent()) {
			project = projectOrNull.get();
		} else {
            String projectName = projectDirectory.getName();
			IWorkspace workspace = ResourcesPlugin.getWorkspace();
			IProjectDescription projectDescription = workspace.newProjectDescription(projectName);
			projectDescription.setLocation(org.eclipse.core.runtime.Path.fromOSString(projectDirectory.getPath()));
			projectDescription.setNatureIds(new String[]{JavaCore.NATURE_ID, BspGradleProjectNature.NATURE_ID});
			ICommand buildSpec = projectDescription.newCommand();
			buildSpec.setBuilderName("org.eclipse.jdt.ls.core.internal.builder.bspBuilder");
			projectDescription.setBuildSpec(new ICommand[]{buildSpec});

			project = workspace.getRoot().getProject(projectName);
            project.create(projectDescription, monitor);

            // open the project
            project.open(IResource.NONE, monitor);

        }

		if (project == null || !project.isAccessible()) {
			return null;
		}

		project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
		return project;
	}

	@Override
	public void reset() {
	}
}
