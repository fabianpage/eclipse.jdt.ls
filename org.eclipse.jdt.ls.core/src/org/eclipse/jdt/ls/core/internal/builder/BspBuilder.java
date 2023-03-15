package org.eclipse.jdt.ls.core.internal.builder;

import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin;
import org.eclipse.jdt.ls.core.internal.managers.BuildTargetsManager;

import ch.epfl.scala.bsp4j.BuildServer;
import ch.epfl.scala.bsp4j.BuildTarget;
import ch.epfl.scala.bsp4j.BuildTargetIdentifier;
import ch.epfl.scala.bsp4j.CompileParams;

/**
 * BspBuilder
 */
public class BspBuilder extends IncrementalProjectBuilder {

    @Override
    protected IProject[] build(int kind, Map<String, String> args, IProgressMonitor monitor) throws CoreException {
        BuildServer buildServer = JavaLanguageServerPlugin.getBuildServer();
        if (buildServer == null) {
            return null;
        }
        List<BuildTarget> targets = BuildTargetsManager.getInstance().getBuildTargets(this.getProject());
        List<BuildTargetIdentifier> ids = targets.stream().map(BuildTarget::getId).toList();
        if (ids != null) {
            buildServer.buildTargetCompile(new CompileParams(ids)).join();
        }
        return null;
    }

}