/*
 ***************************************************************************************
 *  Copyright (C) 2006 EsperTech, Inc. All rights reserved.                            *
 *  http://www.espertech.com/esper                                                     *
 *  http://www.espertech.com                                                           *
 *  ---------------------------------------------------------------------------------- *
 *  The software in this package is published under the terms of the GPL license       *
 *  a copy of which has been included with this distribution in the license.txt file.  *
 ***************************************************************************************
 */
package com.espertech.esper.common.internal.epl.expression.ops;

import com.espertech.esper.common.client.EventBean;
import com.espertech.esper.common.internal.bytecodemodel.base.CodegenBlock;
import com.espertech.esper.common.internal.bytecodemodel.base.CodegenClassScope;
import com.espertech.esper.common.internal.bytecodemodel.base.CodegenMethod;
import com.espertech.esper.common.internal.bytecodemodel.base.CodegenMethodScope;
import com.espertech.esper.common.internal.bytecodemodel.model.expression.CodegenExpression;
import com.espertech.esper.common.internal.epl.expression.codegen.ExprForgeCodegenSymbol;
import com.espertech.esper.common.internal.epl.expression.core.ExprEvaluator;
import com.espertech.esper.common.internal.epl.expression.core.ExprEvaluatorContext;
import com.espertech.esper.common.internal.epl.expression.core.ExprNode;
import com.espertech.esper.common.internal.util.JavaClassHelper;

import static com.espertech.esper.common.internal.bytecodemodel.model.expression.CodegenExpressionBuilder.*;

public class ExprBitWiseNodeForgeEval implements ExprEvaluator {
    private final ExprBitWiseNodeForge forge;
    private final ExprEvaluator lhs;
    private final ExprEvaluator rhs;

    ExprBitWiseNodeForgeEval(ExprBitWiseNodeForge forge, ExprEvaluator lhs, ExprEvaluator rhs) {
        this.forge = forge;
        this.lhs = lhs;
        this.rhs = rhs;
    }

    public Object evaluate(EventBean[] eventsPerStream, boolean isNewData, ExprEvaluatorContext exprEvaluatorContext) {
        Object left = lhs.evaluate(eventsPerStream, isNewData, exprEvaluatorContext);
        Object right = rhs.evaluate(eventsPerStream, isNewData, exprEvaluatorContext);

        if ((left == null) || (right == null)) {
            return null;
        }

        Object result = forge.getComputer().compute(left, right);

        return result;
    }

    public static CodegenExpression codegen(ExprBitWiseNodeForge forge, Class requiredType, CodegenMethodScope codegenMethodScope, ExprForgeCodegenSymbol exprSymbol, CodegenClassScope codegenClassScope, ExprNode lhs, ExprNode rhs) {
        CodegenMethod methodNode = codegenMethodScope.makeChild(forge.getEvaluationType(), ExprBitWiseNodeForgeEval.class, codegenClassScope);

        Class leftType = lhs.getForge().getEvaluationType();
        Class rightType = rhs.getForge().getEvaluationType();
        CodegenBlock block = methodNode.getBlock()
                .declareVar(leftType, "left", lhs.getForge().evaluateCodegen(leftType, methodNode, exprSymbol, codegenClassScope))
                .declareVar(rightType, "right", rhs.getForge().evaluateCodegen(rightType, methodNode, exprSymbol, codegenClassScope));
        if (!leftType.isPrimitive()) {
            block.ifRefNullReturnNull("left");
        }
        if (!rhs.getForge().getEvaluationType().isPrimitive()) {
            block.ifRefNullReturnNull("right");
        }
        Class primitive = JavaClassHelper.getPrimitiveType(forge.getEvaluationType());
        block.declareVar(primitive, "l", ref("left"))
                .declareVar(primitive, "r", ref("right"));

        block.methodReturn(cast(primitive, op(ref("l"), forge.getForgeRenderable().getBitWiseOpEnum().getExpressionText(), ref("r"))));
        return localMethod(methodNode);
    }
}
