/*******************************************************************************
 * Copyright (c) 2020 Red Hat Inc. and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
 * which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 *
 * Contributors:
 *     Red Hat Inc. - initial API and implementation
 *******************************************************************************/
package org.eclipse.lsp4mp.jdt.internal.faulttolerance.java;

import static org.eclipse.lsp4mp.jdt.core.MicroProfileConfigConstants.COMPLETION_STAGE_TYPE_UTILITY;
import static org.eclipse.lsp4mp.jdt.core.MicroProfileConfigConstants.FUTURE_TYPE_UTILITY;
import static org.eclipse.lsp4mp.jdt.core.MicroProfileConfigConstants.UNI_TYPE_UTILITY;
import static org.eclipse.lsp4mp.jdt.core.utils.AnnotationUtils.getAnnotationMemberValueExpression;
import static org.eclipse.lsp4mp.jdt.core.utils.AnnotationUtils.isMatchAnnotation;
import static org.eclipse.lsp4mp.jdt.internal.faulttolerance.MicroProfileFaultToleranceConstants.ASYNCHRONOUS_ANNOTATION;
import static org.eclipse.lsp4mp.jdt.internal.faulttolerance.MicroProfileFaultToleranceConstants.DIAGNOSTIC_SOURCE;
import static org.eclipse.lsp4mp.jdt.internal.faulttolerance.MicroProfileFaultToleranceConstants.FALLBACK_ANNOTATION;
import static org.eclipse.lsp4mp.jdt.internal.faulttolerance.MicroProfileFaultToleranceConstants.FALLBACK_METHOD_FALLBACK_ANNOTATION_MEMBER;
import static org.eclipse.lsp4mp.jdt.internal.faulttolerance.java.MicroProfileFaultToleranceErrorCode.FALLBACK_METHOD_DOES_NOT_EXIST;
import static org.eclipse.lsp4mp.jdt.internal.faulttolerance.java.MicroProfileFaultToleranceErrorCode.FAULT_TOLERANCE_DEFINITION_EXCEPTION;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IOpenable;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.MarkerAnnotation;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.NormalAnnotation;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4mp.jdt.core.java.diagnostics.IJavaDiagnosticsParticipant;
import org.eclipse.lsp4mp.jdt.core.java.diagnostics.JavaDiagnosticsContext;
import org.eclipse.lsp4mp.jdt.core.utils.JDTTypeUtils;

/**
 * Validates that the Fallback annotation's fallback method exists
 *
 */
public class MicroProfileFaultToleranceDiagnosticsParticipant implements IJavaDiagnosticsParticipant {

	@Override
	public boolean isAdaptedForDiagnostics(JavaDiagnosticsContext context, IProgressMonitor monitor)
			throws CoreException {
		IJavaProject javaProject = context.getJavaProject();
		return JDTTypeUtils.findType(javaProject, FALLBACK_ANNOTATION) != null
				|| JDTTypeUtils.findType(javaProject, ASYNCHRONOUS_ANNOTATION) != null;
	}

	@Override
	public List<Diagnostic> collectDiagnostics(JavaDiagnosticsContext context, IProgressMonitor monitor)
			throws CoreException {
		List<Diagnostic> diagnostics = new ArrayList<>();
		validateClass(diagnostics, context, monitor);
		return diagnostics;
	}

	private static void validateClass(List<Diagnostic> diagnostics, JavaDiagnosticsContext context,
			IProgressMonitor monitor) {
		CompilationUnit ast = context.getASTRoot();
		ast.accept(new FaultToleranceAnnotationValidator(diagnostics, context));
	}

	private static class FaultToleranceAnnotationValidator extends ASTVisitor {

		private final Map<TypeDeclaration, Set<String>> methodsCache;
		private List<Diagnostic> diagnostics;
		private JavaDiagnosticsContext context;

		private static Logger LOGGER = Logger.getLogger(FaultToleranceAnnotationValidator.class.getName());

		public FaultToleranceAnnotationValidator(List<Diagnostic> diagnostics, JavaDiagnosticsContext context) {
			super();
			this.methodsCache = new HashMap<>();
			this.diagnostics = diagnostics;
			this.context = context;
		}

		@Override
		public boolean visit(MethodDeclaration node) {
			try {
				validateMethod(node, diagnostics, context);
			} catch (JavaModelException e) {
				LOGGER.log(Level.WARNING,
						"An exception occurred when attempting to validate the annotation marked method");
			}
			super.visit(node);
			return true;
		}

		@Override
		public boolean visit(TypeDeclaration type) {
			@SuppressWarnings("rawtypes")
			List modifiers = type.modifiers();
			for (Object modifier : modifiers) {
				if (modifier instanceof Annotation) {
					Annotation annotation = (Annotation) modifier;
					if (isMatchAnnotation(annotation, ASYNCHRONOUS_ANNOTATION)) {
						try {
							MethodDeclaration[] methods = type.getMethods();
							for (MethodDeclaration node : methods) {
								validateAsynchronousAnnotation(node, diagnostics, context, (MarkerAnnotation) modifier);
							}
						} catch (JavaModelException e) {
							LOGGER.log(Level.WARNING,
									"An exception occurred when attempting to validate the annotation");
						}
						break;
					}
				}
			}
			super.visit(type);
			return true;
		}

		/**
		 * Checks if the given method declaration has a supported annotation, and if so,
		 * provides diagnostics if necessary
		 *
		 * @param node        The method declaration to validate
		 * @param diagnostics A list where the diagnostics will be added
		 * @param context     The context, used to create the diagnostics
		 * @throws JavaModelException
		 */
		private void validateMethod(MethodDeclaration node, List<Diagnostic> diagnostics,
				JavaDiagnosticsContext context) throws JavaModelException {
			@SuppressWarnings("rawtypes")
			List modifiers = node.modifiers();
			for (Object modifier : modifiers) {
				if (modifier instanceof Annotation) {
					Annotation annotation = (Annotation) modifier;
					if (isMatchAnnotation(annotation, FALLBACK_ANNOTATION)) {
						validateFallbackAnnotation(node, diagnostics, context, (NormalAnnotation) modifier);
					} else if (isMatchAnnotation(annotation, ASYNCHRONOUS_ANNOTATION)) {
						validateAsynchronousAnnotation(node, diagnostics, context, (MarkerAnnotation) modifier);
					}
				}
			}
		}

		/**
		 * Checks if the given method declaration has a fallback annotation, and if so,
		 * provides diagnostics if necessary
		 *
		 * @param node        The method declaration to validate
		 * @param diagnostics A list where the diagnostics will be added
		 * @param context     The context, used to create the diagnostics
		 * @param annotation  The @Fallback annotation
		 * @throws JavaModelException
		 */
		private void validateFallbackAnnotation(MethodDeclaration node, List<Diagnostic> diagnostics,
				JavaDiagnosticsContext context, NormalAnnotation annotation) throws JavaModelException {
			Expression fallbackMethodExpr = getAnnotationMemberValueExpression(annotation,
					FALLBACK_METHOD_FALLBACK_ANNOTATION_MEMBER);
			if (fallbackMethodExpr != null) {
				String fallbackMethodName = fallbackMethodExpr.toString();
				fallbackMethodName = fallbackMethodName.substring(1, fallbackMethodName.length() - 1);
				if (!getExistingMethods(node).contains(fallbackMethodName)) {
					IOpenable openable = context.getTypeRoot().getOpenable();
					Diagnostic d = context
							.createDiagnostic(context.getUri(),
									"The referenced fallback method '" + fallbackMethodName + "' does not exist",
									context.getUtils().toRange(openable, fallbackMethodExpr.getStartPosition(),
											fallbackMethodExpr.getLength()),
									DIAGNOSTIC_SOURCE, FALLBACK_METHOD_DOES_NOT_EXIST);
					d.setSeverity(DiagnosticSeverity.Error);
					diagnostics.add(d);
				}
			}
		}

		/**
		 * Checks if the given method declaration has an asynchronous annotation, and if
		 * so, provides diagnostics for the method return type
		 *
		 * @param node        The method declaration to validate
		 * @param diagnostics A list where the diagnostics will be added
		 * @param context     The context, used to create the diagnostics
		 * @param annotation  The @Asynchronous annotation
		 * @throws JavaModelException
		 */
		private void validateAsynchronousAnnotation(MethodDeclaration node, List<Diagnostic> diagnostics,
				JavaDiagnosticsContext context, MarkerAnnotation annotation) throws JavaModelException {
			Type methodReturnType = node.getReturnType2();
			String methodReturnTypeString;
			try {
				methodReturnTypeString = methodReturnType.resolveBinding().getErasure().getQualifiedName();
			} catch (Exception e) {
				throw e;
			}
			if ((!(FUTURE_TYPE_UTILITY.equals(methodReturnTypeString))
					&& !(COMPLETION_STAGE_TYPE_UTILITY.equals(methodReturnTypeString))
					&& !(UNI_TYPE_UTILITY.equals(methodReturnTypeString)))) {
				IOpenable openable = context.getTypeRoot().getOpenable();
				Diagnostic d = context
						.createDiagnostic(context.getUri(),
								"The annotated method does not return an object of type Future, CompletionStage or Uni",
								context.getUtils().toRange(openable, methodReturnType.getStartPosition(),
										methodReturnType.getLength()),
								DIAGNOSTIC_SOURCE, FAULT_TOLERANCE_DEFINITION_EXCEPTION);
				d.setSeverity(DiagnosticSeverity.Error);
				diagnostics.add(d);
			}
		}

		private Set<String> getExistingMethods(MethodDeclaration node) {
			TypeDeclaration type = getOwnerType(node);
			if (type == null) {
				return Collections.emptySet();
			}
			return getExistingMethods(type);
		}

		private TypeDeclaration getOwnerType(ASTNode node) {
			while (node != null) {
				if (node instanceof TypeDeclaration) {
					return (TypeDeclaration) node;
				}
				node = node.getParent();
			}
			return null;
		}

		private Set<String> getExistingMethods(TypeDeclaration type) {
			Set<String> methods = methodsCache.get(type);
			if (methods == null) {
				methods = Stream.of(type.getMethods()) //
						.map(m -> {
							return m.getName().getIdentifier();
						}).collect(Collectors.toUnmodifiableSet());
				methodsCache.put(type, methods);
			}
			return methods;
		};
	}

}