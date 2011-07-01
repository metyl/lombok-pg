/*
 * Copyright © 2011 Philipp Eichhorn
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package lombok.javac.handlers;

import static lombok.ast.AST.*;
import static lombok.core.util.ErrorMessages.*;
import static lombok.javac.handlers.Javac.deleteMethodCallImports;
import static lombok.javac.handlers.Javac.isMethodCallValid;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import lombok.Tuple;
import lombok.javac.JavacASTAdapter;
import lombok.javac.JavacASTVisitor;
import lombok.javac.JavacNode;
import lombok.javac.handlers.ast.JavacASTMaker;
import lombok.javac.handlers.ast.JavacMethod;

import org.mangosdk.spi.ProviderFor;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreeScanner;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCAssign;
import com.sun.tools.javac.tree.JCTree.JCBlock;
import com.sun.tools.javac.tree.JCTree.JCCase;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCIdent;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCMethodInvocation;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;

@ProviderFor(JavacASTVisitor.class)
public class HandleTuple extends JavacASTAdapter {
	private final Set<String> methodNames = new HashSet<String>();
	private int withVarCounter;

	@Override public void visitCompilationUnit(JavacNode top, JCCompilationUnit unit) {
		methodNames.clear();
		withVarCounter = 0;
	}

	@Override public void visitStatement(JavacNode statementNode, JCTree statement) {
		if (statement instanceof JCAssign) {
			final JCAssign assignment = (JCAssign) statement;
			final JCMethodInvocation leftTupleCall = getTupelCall(statementNode, assignment.lhs);
			final JCMethodInvocation rightTupleCall = getTupelCall(statementNode, assignment.rhs);
			if ((leftTupleCall != null) && (rightTupleCall != null)) {
				final JavacMethod method = JavacMethod.methodOf(statementNode, statement);
				if (method == null) {
					statementNode.addError(canBeUsedInBodyOfMethodsOnly("tuple"));
				} else if (handle(statementNode, leftTupleCall, rightTupleCall)) {
					methodNames.add(leftTupleCall.meth.toString());
					methodNames.add(rightTupleCall.meth.toString());
				}
			}
		}
	}

	private JCMethodInvocation getTupelCall(JavacNode node, JCExpression expression) {
		if (expression instanceof JCMethodInvocation) {
			final JCMethodInvocation tupleCall = (JCMethodInvocation) expression ;
			final String methodName = tupleCall.meth.toString();
			if (isMethodCallValid(node, methodName, Tuple.class, "tuple")) {
				return tupleCall;
			}
		}
		return null;
	}

	@Override public void endVisitCompilationUnit(JavacNode top, JCCompilationUnit unit) {
		for (String methodName : methodNames) {
			deleteMethodCallImports(top, methodName, Tuple.class, "tuple");
		}
	}

	public boolean handle(JavacNode tupleAssignNode, JCMethodInvocation leftTupleCall, JCMethodInvocation rightTupleCall) {
		if (leftTupleCall.args.length() != rightTupleCall.args.length()) {
			tupleAssignNode.addError("The left and right hand side of the assignment must have the same amount of arguments for the tuple assignment to work.");
			return false;
		}
		if (!containsOnlyNames(leftTupleCall.args)) {
			tupleAssignNode.addError("Only variable names are allowed as arguments of the left hand side in a tuple assignment.");
			return false;
		}

		ListBuffer<JCStatement> tempVarAssignments = ListBuffer.lb();
		ListBuffer<JCStatement> assignments = ListBuffer.lb();

		List<String> varnames = collectVarnames(leftTupleCall.args);
		Iterator<String> varnameIter = varnames.listIterator();
		JavacASTMaker builder = new JavacASTMaker(tupleAssignNode, leftTupleCall);

		final Set<String> blacklistedNames = new HashSet<String>();
		for (JCExpression arg : rightTupleCall.args) {
			String varname = varnameIter.next();
			final Boolean canUseSimpleAssignment = new SimpleAssignmentAnalyser(blacklistedNames).scan(arg, null);
			blacklistedNames.add(varname);
			if ((canUseSimpleAssignment != null) && !canUseSimpleAssignment) {
				final JCExpression vartype = new VarTypeFinder(varname, tupleAssignNode.get()).scan(tupleAssignNode.top().get(), null);
				if (vartype != null) {
					String tempVarname = "$tuple" + withVarCounter++;
					tempVarAssignments.append(builder.build(LocalDecl(Type(vartype), tempVarname).makeFinal().withInitialization(Expr(arg)), JCStatement.class));
					assignments.append(builder.build(Assign(Name(varname), Name(tempVarname)), JCStatement.class));
				} else {
					tupleAssignNode.addError("Lombok-pg Bug. Unable to find vartype.");
					return false;
				}
			} else {
				assignments.append(builder.build(Assign(Name(varname), Expr(arg)), JCStatement.class));
			}
		}
		tempVarAssignments.appendList(assignments);
		tryToInjectStatements(tupleAssignNode, tupleAssignNode.get(), tempVarAssignments.toList());

		return true;
	}

	private void tryToInjectStatements(JavacNode parent, JCTree statementThatUsesTupel, List<JCStatement> statementsToInject) {
		while (!(statementThatUsesTupel instanceof JCStatement)) {
			parent = parent.directUp();
			statementThatUsesTupel = parent.get();
		}
		JCStatement statement = (JCStatement) statementThatUsesTupel;
		JavacNode grandParent = parent.directUp();
		JCTree block = grandParent.get();
		if (block instanceof JCBlock) {
			((JCBlock)block).stats = injectStatements(((JCBlock)block).stats, statement, statementsToInject);
		} else if (block instanceof JCCase) {
			((JCCase)block).stats = injectStatements(((JCCase)block).stats, statement, statementsToInject);
		} else if (block instanceof JCMethodDecl) {
			((JCMethodDecl)block).body.stats = injectStatements(((JCMethodDecl)block).body.stats, statement, statementsToInject);
		} else {
			// this would be odd odd but what the hell
			return;
		}
		grandParent.rebuild();
	}

	private List<JCStatement> injectStatements(List<JCStatement> statements, JCStatement statement, List<JCStatement> statementsToInject) {
		final ListBuffer<JCStatement> newStatements = ListBuffer.lb();
		for (JCStatement stat : statements) {
			if (stat == statement) {
				newStatements.appendList(statementsToInject);
			} else newStatements.append(stat);
		}
		return newStatements.toList();
	}

	private List<String> collectVarnames(List<JCExpression> expressions) {
		ListBuffer<String> varnames = ListBuffer.lb();
		for (JCExpression expression : expressions) {
				varnames.append(expression.toString());
		}
		return varnames.toList();
	}

	private boolean containsOnlyNames(List<JCExpression> expressions) {
		for (JCExpression expression : expressions) {
			if (!(expression instanceof JCIdent)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Look for the type of a variable in the scope of the given expression.
	 * <p>
	 * {@link VarTypeFinder#scan(com.sun.source.tree.Tree, Void) VarTypeFinder.scan(Tree, Void)} will
	 * return the type of a variable in the scope of the given expression.
	 */
	@RequiredArgsConstructor
	private static class VarTypeFinder extends TreeScanner<JCExpression, Void> {
		private final String varname;
		private final JCTree expr;
		private boolean lockVarname;

		@Override public JCExpression visitVariable(VariableTree node, Void p) {
			if (!lockVarname && varname.equals(node.getName().toString())) {
				return (JCExpression) node.getType();
			}
			return null;
		}

		@Override public JCExpression visitAssignment(AssignmentTree node, Void p) {
			if ((expr != null) && (expr.equals(node))) {
				lockVarname = true;
			}
			return super.visitAssignment(node, p);
		}

		@Override
		public JCExpression reduce(JCExpression r1, JCExpression r2) {
			return (r1 != null) ? r1 : r2;
		}
	}

	/**
	 * Look for variable names that would break a simple assignment after transforming the tuple.<br>
	 * So look for the use of already changed values (caused the tuple assignment) in the given expression.
	 * <p>
	 * If {@link SimpleAssignmentAnalyser#scan(com.sun.source.tree.Tree, Void) AssignmentAnalyser.scan(Tree, Void)}
	 * return {@code null} or {@code true} everything is fine, otherwise a temporary assignment is needed.
	 */
	@RequiredArgsConstructor
	private static class SimpleAssignmentAnalyser extends TreeScanner<Boolean, Void> {
		private final Set<String> blacklistedVarnames;

		@Override public Boolean visitMemberSelect(MemberSelectTree node, Void p) {
			return Boolean.TRUE;
		}

		@Override public Boolean visitIdentifier(IdentifierTree node, Void p) {
			return !blacklistedVarnames.contains(node.getName().toString());
		}

		@Override public Boolean reduce(Boolean r1, Boolean r2) {
			if ((r1 == Boolean.FALSE) || (r2 == Boolean.FALSE)) {
				return Boolean.FALSE;
			}
			return Boolean.TRUE;
		}
	}
}