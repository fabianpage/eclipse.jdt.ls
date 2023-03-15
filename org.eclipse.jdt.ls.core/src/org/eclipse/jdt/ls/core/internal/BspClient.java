package org.eclipse.jdt.ls.core.internal;

import java.util.LinkedList;
import java.util.List;

import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

import ch.epfl.scala.bsp4j.BuildClient;
import ch.epfl.scala.bsp4j.Diagnostic;
import ch.epfl.scala.bsp4j.DidChangeBuildTarget;
import ch.epfl.scala.bsp4j.LogMessageParams;
import ch.epfl.scala.bsp4j.PublishDiagnosticsParams;
import ch.epfl.scala.bsp4j.ShowMessageParams;
import ch.epfl.scala.bsp4j.TaskFinishParams;
import ch.epfl.scala.bsp4j.TaskProgressParams;
import ch.epfl.scala.bsp4j.TaskStartParams;

public class BspClient implements BuildClient {

	@Override
	public void onBuildShowMessage(ShowMessageParams params) {
	}

	@Override
	public void onBuildLogMessage(LogMessageParams params) {
	}

	@Override
	public void onBuildTaskStart(TaskStartParams params) {
	}

	@Override
	public void onBuildTaskProgress(TaskProgressParams params) {
	}

	@Override
	public void onBuildTaskFinish(TaskFinishParams params) {
	}

	@Override
	public void onBuildPublishDiagnostics(PublishDiagnosticsParams params) {
		org.eclipse.lsp4j.PublishDiagnosticsParams lspDiagnostics = new org.eclipse.lsp4j.PublishDiagnosticsParams();
		lspDiagnostics.setUri(params.getTextDocument().getUri());
		List<org.eclipse.lsp4j.Diagnostic> diagnostics = new LinkedList<>();
		for (Diagnostic bspDiagnostic : params.getDiagnostics()) {
			org.eclipse.lsp4j.Diagnostic d = new org.eclipse.lsp4j.Diagnostic();
			d.setRange(new Range(
				new Position(bspDiagnostic.getRange().getStart().getLine(), bspDiagnostic.getRange().getStart().getCharacter()),
				new Position(bspDiagnostic.getRange().getEnd().getLine(), bspDiagnostic.getRange().getEnd().getCharacter())
			));
			d.setMessage(bspDiagnostic.getMessage());
			d.setSeverity(DiagnosticSeverity.Error);
			diagnostics.add(d);
		}
		lspDiagnostics.setDiagnostics(diagnostics);
		JavaLanguageServerPlugin.getInstance().getClientConnection().publishDiagnostics(lspDiagnostics);
	}

	@Override
	public void onBuildTargetDidChange(DidChangeBuildTarget params) {
	}
}