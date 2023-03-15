package org.eclipse.jdt.ls.core.internal.managers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;

import ch.epfl.scala.bsp4j.BuildTarget;

public class BuildTargetsManager {
    private BuildTargetsManager() {
    }

    private static class BuildTargetsManagerHolder {
        private static final BuildTargetsManager INSTANCE = new BuildTargetsManager();
    }

    public static BuildTargetsManager getInstance() {
        return BuildTargetsManagerHolder.INSTANCE;
    }

    private Map<IProject, List<BuildTarget>> cache = new HashMap<>();

    public void reset() {
        cache.clear();
    }

    public List<BuildTarget> getBuildTargets(IProject project) {
        return cache.get(project);
    }

    public void setBuildTargets(IProject project, List<BuildTarget> targets) {
        cache.put(project, targets);
    }
}
