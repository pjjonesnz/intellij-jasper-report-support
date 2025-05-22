/*
 * Project intellij-jasper-report-support
 *
 * Copyright 2023-2023 Alessandro 'kLeZ' Accardo
 * Previous copyright (c) 2017-2023 Chathura Buddhika
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 */

package me.klez.actions;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperCompileManager;
import org.jetbrains.annotations.NotNull;

import javax.xml.stream.XMLInputFactory;
import java.io.File;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class CompileJrxmlAction extends AnAction {

	public static final String SOURCE_EXT = ".jrxml";
	public static final String DEST_EXT = ".jasper";
	public static final String NOTIFICATION_GROUP = "Jasper Report Support Notification Group";

	private static void debugLog(String message, Project project) {
		// Print to console
		System.out.println("[JASPER DEBUG] " + message);
	}

	private static Result buildReport(VirtualFile vf, Project project)  {
		final Result.ResultBuilder builder = Result.builder().source(vf.getPath()).destination(vf.getPath().replace(SOURCE_EXT, DEST_EXT));

		debugLog("=== Starting JRXML Compilation ===", project);
		debugLog("Source file: " + vf.getPath(), project);
		debugLog("VirtualFile exists: " + vf.exists(), project);
		debugLog("VirtualFile is valid: " + vf.isValid(), project);
		debugLog("VirtualFile size: " + vf.getLength(), project);

		// Check if source file actually exists on filesystem
		File sourceFile = new File(vf.getPath());
		debugLog("Physical file exists: " + sourceFile.exists(), project);
		debugLog("Physical file can read: " + sourceFile.canRead(), project);
		debugLog("Physical file size: " + sourceFile.length(), project);
		debugLog("Physical file absolute path: " + sourceFile.getAbsolutePath(), project);

		ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();

		try {
			if (!sourceFile.exists()) {
				throw new JRException("Source file does not exist on filesystem: " + sourceFile.getAbsolutePath());
			}

			if (!sourceFile.canRead()) {
				throw new JRException("Cannot read source file: " + sourceFile.getAbsolutePath());
			}

			if (sourceFile.length() == 0) {
				throw new JRException("Source file is empty: " + sourceFile.getAbsolutePath());
			}
			Thread.currentThread().setContextClassLoader(CompileJrxmlAction.class.getClassLoader());
			try {
				Class<?> factoryClass = XMLInputFactory.class;
				Method method = factoryClass.getDeclaredMethod("newFactory", String.class, ClassLoader.class);
				method.setAccessible(true);
			} catch (Exception e) {
				// Ignore reflection failures, continue with normal compilation
			}
			debugLog("About to call JasperCompileManager.compileReportToFile", project);
			debugLog("Source: " + builder.getSource(), project);
			debugLog("Destination: " + builder.getDestination(), project);

			JasperCompileManager.compileReportToFile(builder.getSource(), builder.getDestination());
			builder.type(ResultType.OK);
		} catch (JRException e) {
			builder.type(ResultType.KO).message(e.getMessage()).exception(e);
		} finally {
			// Restore original classloader
			Thread.currentThread().setContextClassLoader(originalClassLoader);
		}
		return builder.build();
	}

	private static String getResultMessage(Result r) {
		return String.format("%s: %s%s", r.getSource(), r.getType(), getKoMessage(r));
	}

	private static String getKoMessage(Result r) {
		return r.getType() == ResultType.KO ? String.format(" (%s)", r.getMessage()) : "";
	}

	/**
	 * Implement this method to provide your action handler.
	 *
	 * @param e
	 * 		Carries information on the invocation place
	 */
	@Override
	public void actionPerformed(@NotNull AnActionEvent e) {
		if (ActionPlaces.isMainMenuOrActionSearch(e.getPlace())) {
			Optional.ofNullable(e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY))
			        .map(Stream::of)
			        .map(stream -> stream.filter(vf -> vf.isValid() && vf.exists() && !vf.isDirectory())
			                             .filter(vf -> vf.getPath().endsWith(SOURCE_EXT))
			                             .map(vf -> buildReport(vf, e.getProject()))
			                             .collect(Collectors.toList()))
			        .ifPresent(resultList -> createBalloon(resultList, e.getProject()));
		}
	}

	private void createBalloon(Collection<Result> resultList, Project project) {
		long okCount = resultList.stream().filter(r -> r.getType() == ResultType.OK).count();
		long koCount = resultList.stream().filter(r -> r.getType() == ResultType.KO).count();
		NotificationType notificationType;
		if (koCount == 0) {
			notificationType = NotificationType.INFORMATION;
		} else {
			if (okCount == 0) {
				notificationType = NotificationType.ERROR;
			} else {
				notificationType = NotificationType.WARNING;
			}
		}
		String content = resultList.stream().map(CompileJrxmlAction::getResultMessage).collect(Collectors.joining(System.lineSeparator()));
		NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP).createNotification(content, notificationType).notify(project);
	}
}
