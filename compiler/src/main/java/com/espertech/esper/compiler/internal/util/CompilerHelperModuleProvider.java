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
package com.espertech.esper.compiler.internal.util;

import com.espertech.esper.common.client.EPCompiled;
import com.espertech.esper.common.client.EPCompiledManifest;
import com.espertech.esper.common.client.EventType;
import com.espertech.esper.common.client.meta.EventTypeMetadata;
import com.espertech.esper.common.client.module.ModuleProperty;
import com.espertech.esper.common.internal.bytecodemodel.base.*;
import com.espertech.esper.common.internal.bytecodemodel.core.CodeGenerationIDGenerator;
import com.espertech.esper.common.internal.bytecodemodel.core.CodegenClass;
import com.espertech.esper.common.internal.bytecodemodel.core.CodegenClassMethods;
import com.espertech.esper.common.internal.bytecodemodel.model.expression.CodegenExpression;
import com.espertech.esper.common.internal.bytecodemodel.model.expression.CodegenExpressionBuilder;
import com.espertech.esper.common.internal.bytecodemodel.model.expression.CodegenExpressionRef;
import com.espertech.esper.common.internal.bytecodemodel.util.CodegenStackGenerator;
import com.espertech.esper.common.internal.bytecodemodel.util.IdentifierUtil;
import com.espertech.esper.common.internal.compile.stage1.Compilable;
import com.espertech.esper.common.internal.compile.stage1.spec.ExpressionDeclItem;
import com.espertech.esper.common.internal.compile.stage1.spec.ExpressionScriptProvided;
import com.espertech.esper.common.internal.compile.stage2.StatementSpecCompileException;
import com.espertech.esper.common.internal.compile.stage2.StatementSpecCompileSyntaxException;
import com.espertech.esper.common.internal.compile.stage3.ModuleCompileTimeServices;
import com.espertech.esper.common.internal.compile.stage3.StatementCompileTimeServices;
import com.espertech.esper.common.internal.context.aifactory.core.*;
import com.espertech.esper.common.internal.context.compile.ContextMetaData;
import com.espertech.esper.common.internal.context.module.*;
import com.espertech.esper.common.internal.epl.index.compile.IndexCompileTimeKey;
import com.espertech.esper.common.internal.epl.index.compile.IndexDetail;
import com.espertech.esper.common.internal.epl.index.compile.IndexDetailForge;
import com.espertech.esper.common.internal.epl.namedwindow.path.NamedWindowMetaData;
import com.espertech.esper.common.internal.epl.script.core.NameAndParamNum;
import com.espertech.esper.common.internal.epl.table.compiletime.TableMetaData;
import com.espertech.esper.common.internal.epl.variable.compiletime.VariableMetaData;
import com.espertech.esper.common.internal.event.avro.AvroSchemaEventType;
import com.espertech.esper.common.internal.event.bean.core.BeanEventType;
import com.espertech.esper.common.internal.event.core.BaseNestableEventType;
import com.espertech.esper.common.internal.event.core.EventTypeUtility;
import com.espertech.esper.common.internal.event.core.TypeBeanOrUnderlying;
import com.espertech.esper.common.internal.event.core.WrapperEventType;
import com.espertech.esper.common.internal.event.map.MapEventType;
import com.espertech.esper.common.internal.event.variant.VariantEventType;
import com.espertech.esper.common.internal.event.xml.SchemaXMLEventType;
import com.espertech.esper.common.internal.util.CollectionUtil;
import com.espertech.esper.common.internal.util.SerializerUtil;
import com.espertech.esper.compiler.client.CompilerOptions;
import com.espertech.esper.compiler.client.EPCompileException;
import com.espertech.esper.compiler.client.EPCompileExceptionItem;
import com.espertech.esper.compiler.client.EPCompileExceptionSyntaxItem;

import java.io.IOException;
import java.util.*;
import java.util.function.Supplier;

import static com.espertech.esper.common.internal.bytecodemodel.model.expression.CodegenExpressionBuilder.*;
import static com.espertech.esper.compiler.internal.util.CompilerHelperStatementProvider.compileItem;
import static com.espertech.esper.compiler.internal.util.Version.COMPILER_VERSION;

public class CompilerHelperModuleProvider {
    protected static EPCompiled compile(List<Compilable> compilables, String optionalModuleName, Map<ModuleProperty, Object> moduleProperties, ModuleCompileTimeServices compileTimeServices, CompilerOptions compilerOptions) throws EPCompileException {
        String packageName = "generated";
        Map<String, byte[]> moduleBytes = new HashMap<>();
        EPCompiledManifest manifest;
        try {
            manifest = compileToBytes(moduleBytes, compilables, optionalModuleName, moduleProperties, compileTimeServices, compilerOptions, packageName);
        } catch (EPCompileException ex) {
            throw ex;
        } catch (Throwable t) {
            throw new EPCompileException("Unexpected exception compiling module: " + t.getMessage(), t, Collections.emptyList());
        }
        return new EPCompiled(moduleBytes, manifest);
    }

    private static EPCompiledManifest compileToBytes(Map<String, byte[]> moduleBytes, List<Compilable> compilables, String optionalModuleName, Map<ModuleProperty, Object> moduleProperties, ModuleCompileTimeServices compileTimeServices, CompilerOptions compilerOptions, String packageName) throws EPCompileException, IOException {
        String moduleAssignedName = optionalModuleName == null ? UUID.randomUUID().toString() : optionalModuleName;
        String moduleIdentPostfix = IdentifierUtil.getIdentifierMayStartNumeric(moduleAssignedName);

        // compile each statement
        int statementNumber = 0;
        List<String> statementClassNames = new ArrayList<>();

        Set<String> statementNames = new HashSet<>();
        for (Compilable compilable : compilables) {
            String className;

            try {
                StatementCompileTimeServices statementCompileTimeServices = new StatementCompileTimeServices(statementNumber, compileTimeServices);
                className = compileItem(compilable, optionalModuleName, moduleIdentPostfix, moduleBytes, statementNumber, packageName, statementNames, statementCompileTimeServices, compilerOptions);
            } catch (StatementSpecCompileException ex) {
                EPCompileExceptionItem first;
                if (ex instanceof StatementSpecCompileSyntaxException) {
                    first = new EPCompileExceptionSyntaxItem(ex.getMessage(), ex.getExpression(), -1);
                } else {
                    first = new EPCompileExceptionItem(ex.getMessage(), ex.getExpression(), -1);
                }
                List<EPCompileExceptionItem> items = Collections.singletonList(first);
                throw new EPCompileException(ex.getMessage() + " [" + ex.getExpression() + "]", ex, items);
            }

            statementClassNames.add(className);
            statementNumber++;
        }

        // compile module resource
        String moduleProviderClassName = compileModule(optionalModuleName, moduleProperties, statementClassNames, moduleIdentPostfix, moduleBytes, packageName, compileTimeServices);

        // create module XML
        return new EPCompiledManifest(COMPILER_VERSION, moduleProviderClassName, null);
    }

    private static String compileModule(String optionalModuleName, Map<ModuleProperty, Object> moduleProperties, List<String> statementClassNames, String moduleIdentPostfix, Map<String, byte[]> moduleBytes, String packageName, ModuleCompileTimeServices compileTimeServices) {
        // write code to create an implementation of StatementResource
        CodegenPackageScope packageScope = new CodegenPackageScope(packageName, null, compileTimeServices.isInstrumented());
        String moduleClassName = CodeGenerationIDGenerator.generateClassNameSimple(ModuleProvider.class, moduleIdentPostfix);
        CodegenClassScope classScope = new CodegenClassScope(true, packageScope, moduleClassName);
        CodegenClassMethods methods = new CodegenClassMethods();

        // provide module name
        CodegenMethod getModuleNameMethod = CodegenMethod.makeParentNode(String.class, EPCompilerImpl.class, CodegenSymbolProviderEmpty.INSTANCE, classScope);
        getModuleNameMethod.getBlock().methodReturn(constant(optionalModuleName));

        // provide module properties
        CodegenMethod getModulePropertiesMethod = CodegenMethod.makeParentNode(Map.class, EPCompilerImpl.class, CodegenSymbolProviderEmpty.INSTANCE, classScope);
        makeModuleProperties(moduleProperties, getModulePropertiesMethod);

        // provide module dependencies
        CodegenMethod getModuleDependenciesMethod = CodegenMethod.makeParentNode(ModuleDependenciesRuntime.class, EPCompilerImpl.class, CodegenSymbolProviderEmpty.INSTANCE, classScope);
        getModuleDependenciesMethod.getBlock().methodReturn(compileTimeServices.getModuleDependencies().make(getModuleDependenciesMethod, classScope));

        // register types
        CodegenMethod initializeEventTypesMethod = makeInitEventTypes(classScope, compileTimeServices);

        // register named windows
        ModuleNamedWindowInitializeSymbol symbolsNamedWindowInit = new ModuleNamedWindowInitializeSymbol();
        CodegenMethod initializeNamedWindowsMethod = CodegenMethod.makeParentNode(void.class, EPCompilerImpl.class, symbolsNamedWindowInit, classScope).addParam(EPModuleNamedWindowInitServices.class, ModuleNamedWindowInitializeSymbol.REF_INITSVC.getRef());
        for (Map.Entry<String, NamedWindowMetaData> namedWindow : compileTimeServices.getNamedWindowCompileTimeRegistry().getNamedWindows().entrySet()) {
            CodegenMethod addNamedWindow = registerNamedWindowCodegen(namedWindow, initializeNamedWindowsMethod, classScope, symbolsNamedWindowInit);
            initializeNamedWindowsMethod.getBlock().expression(localMethod(addNamedWindow));
        }

        // register tables
        ModuleTableInitializeSymbol symbolsTableInit = new ModuleTableInitializeSymbol();
        CodegenMethod initializeTablesMethod = CodegenMethod.makeParentNode(void.class, EPCompilerImpl.class, symbolsTableInit, classScope).addParam(EPModuleTableInitServices.class, ModuleTableInitializeSymbol.REF_INITSVC.getRef());
        for (Map.Entry<String, TableMetaData> table : compileTimeServices.getTableCompileTimeRegistry().getTables().entrySet()) {
            CodegenMethod addTable = registerTableCodegen(table, initializeTablesMethod, classScope, symbolsTableInit);
            initializeTablesMethod.getBlock().expression(localMethod(addTable));
        }

        // register indexes
        ModuleIndexesInitializeSymbol symbolsIndexInit = new ModuleIndexesInitializeSymbol();
        CodegenMethod initializeIndexesMethod = CodegenMethod.makeParentNode(void.class, EPCompilerImpl.class, symbolsIndexInit, classScope).addParam(EPModuleIndexInitServices.class, EPModuleIndexInitServices.REF.getRef());
        for (Map.Entry<IndexCompileTimeKey, IndexDetailForge> index : compileTimeServices.getIndexCompileTimeRegistry().getIndexes().entrySet()) {
            CodegenMethod addIndex = registerIndexCodegen(index, initializeIndexesMethod, classScope, symbolsIndexInit);
            initializeIndexesMethod.getBlock().expression(localMethod(addIndex));
        }

        // register contexts
        ModuleContextInitializeSymbol symbolsContextInit = new ModuleContextInitializeSymbol();
        CodegenMethod initializeContextsMethod = CodegenMethod.makeParentNode(void.class, EPCompilerImpl.class, symbolsContextInit, classScope).addParam(EPModuleContextInitServices.class, ModuleContextInitializeSymbol.REF_INITSVC.getRef());
        for (Map.Entry<String, ContextMetaData> context : compileTimeServices.getContextCompileTimeRegistry().getContexts().entrySet()) {
            CodegenMethod addContext = registerContextCodegen(context, initializeContextsMethod, classScope, symbolsContextInit);
            initializeContextsMethod.getBlock().expression(localMethod(addContext));
        }

        // register variables
        ModuleVariableInitializeSymbol symbolsVariablesInit = new ModuleVariableInitializeSymbol();
        CodegenMethod initializeVariablesMethod = CodegenMethod.makeParentNode(void.class, EPCompilerImpl.class, symbolsVariablesInit, classScope).addParam(EPModuleVariableInitServices.class, ModuleVariableInitializeSymbol.REF_INITSVC.getRef());
        for (Map.Entry<String, VariableMetaData> variable : compileTimeServices.getVariableCompileTimeRegistry().getVariables().entrySet()) {
            CodegenMethod addVariable = registerVariableCodegen(variable, initializeVariablesMethod, classScope, symbolsVariablesInit);
            initializeVariablesMethod.getBlock().expression(localMethod(addVariable));
        }

        // register expressions
        ModuleExpressionDeclaredInitializeSymbol symbolsExprDeclaredInit = new ModuleExpressionDeclaredInitializeSymbol();
        CodegenMethod initializeExprDeclaredMethod = CodegenMethod.makeParentNode(void.class, EPCompilerImpl.class, symbolsExprDeclaredInit, classScope).addParam(EPModuleExprDeclaredInitServices.class, ModuleExpressionDeclaredInitializeSymbol.REF_INITSVC.getRef());
        for (Map.Entry<String, ExpressionDeclItem> expression : compileTimeServices.getExprDeclaredCompileTimeRegistry().getExpressions().entrySet()) {
            CodegenMethod addExpression = registerExprDeclaredCodegen(expression, initializeExprDeclaredMethod, classScope, symbolsExprDeclaredInit);
            initializeExprDeclaredMethod.getBlock().expression(localMethod(addExpression));
        }

        // register scripts
        ModuleScriptInitializeSymbol symbolsScriptInit = new ModuleScriptInitializeSymbol();
        CodegenMethod initializeScriptsMethod = CodegenMethod.makeParentNode(void.class, EPCompilerImpl.class, symbolsScriptInit, classScope).addParam(EPModuleScriptInitServices.class, ModuleScriptInitializeSymbol.REF_INITSVC.getRef());
        for (Map.Entry<NameAndParamNum, ExpressionScriptProvided> expression : compileTimeServices.getScriptCompileTimeRegistry().getScripts().entrySet()) {
            CodegenMethod addScript = registerScriptCodegen(expression, initializeScriptsMethod, classScope, symbolsScriptInit);
            initializeScriptsMethod.getBlock().expression(localMethod(addScript));
        }

        // instantiate factories for statements
        CodegenMethod statementsMethod = CodegenMethod.makeParentNode(List.class, EPCompilerImpl.class, CodegenSymbolProviderEmpty.INSTANCE, classScope);
        statementsMethod.getBlock().declareVar(List.class, "statements", newInstance(ArrayList.class, constant(statementClassNames.size())));
        for (String statementClassName : statementClassNames) {
            statementsMethod.getBlock().exprDotMethod(ref("statements"), "add", CodegenExpressionBuilder.newInstance(statementClassName));
        }
        statementsMethod.getBlock().methodReturn(ref("statements"));

        // build stack
        CodegenStackGenerator.recursiveBuildStack(getModuleNameMethod, "getModuleName", methods);
        CodegenStackGenerator.recursiveBuildStack(getModulePropertiesMethod, "getModuleProperties", methods);
        CodegenStackGenerator.recursiveBuildStack(getModuleDependenciesMethod, "getModuleDependencies", methods);
        CodegenStackGenerator.recursiveBuildStack(initializeEventTypesMethod, "initializeEventTypes", methods);
        CodegenStackGenerator.recursiveBuildStack(initializeNamedWindowsMethod, "initializeNamedWindows", methods);
        CodegenStackGenerator.recursiveBuildStack(initializeTablesMethod, "initializeTables", methods);
        CodegenStackGenerator.recursiveBuildStack(initializeIndexesMethod, "initializeIndexes", methods);
        CodegenStackGenerator.recursiveBuildStack(initializeContextsMethod, "initializeContexts", methods);
        CodegenStackGenerator.recursiveBuildStack(initializeVariablesMethod, "initializeVariables", methods);
        CodegenStackGenerator.recursiveBuildStack(initializeExprDeclaredMethod, "initializeExprDeclareds", methods);
        CodegenStackGenerator.recursiveBuildStack(initializeScriptsMethod, "initializeScripts", methods);
        CodegenStackGenerator.recursiveBuildStack(statementsMethod, "statements", methods);

        CodegenClass clazz = new CodegenClass(ModuleProvider.class, packageName, moduleClassName, classScope, Collections.emptyList(), null, methods, Collections.emptyList());
        JaninoCompiler.compile(clazz, moduleBytes, compileTimeServices.getConfiguration().getCompiler().getLogging().isEnableCode());

        return CodeGenerationIDGenerator.generateClassNameWithPackage(packageName, ModuleProvider.class, moduleIdentPostfix);
    }

    private static void makeModuleProperties(Map<ModuleProperty, Object> props, CodegenMethod method) {
        if (props.isEmpty()) {
            method.getBlock().methodReturn(staticMethod(Collections.class, "emptyMap"));
            return;
        }
        if (props.size() == 1) {
            Map.Entry<ModuleProperty, Object> entry = props.entrySet().iterator().next();
            method.getBlock().methodReturn(staticMethod(Collections.class, "singletonMap", makeModulePropKey(entry.getKey()), makeModulePropValue(entry.getValue())));
            return;
        }
        method.getBlock().declareVar(Map.class, "props", newInstance(HashMap.class, constant(CollectionUtil.capacityHashMap(props.size()))));
        for (Map.Entry<ModuleProperty, Object> entry : props.entrySet()) {
            method.getBlock().exprDotMethod(ref("props"), "put", makeModulePropKey(entry.getKey()), makeModulePropValue(entry.getValue()));
        }
        method.getBlock().methodReturn(ref("props"));
    }

    private static CodegenExpression makeModulePropKey(ModuleProperty key) {
        return enumValue(ModuleProperty.class, key.name());
    }

    private static CodegenExpression makeModulePropValue(Object value) {
        return SerializerUtil.expressionForUserObject(value);
    }

    private static CodegenMethod registerScriptCodegen(Map.Entry<NameAndParamNum, ExpressionScriptProvided> script, CodegenMethodScope parent, CodegenClassScope classScope, ModuleScriptInitializeSymbol symbols) {
        CodegenMethod method = parent.makeChild(void.class, EPCompilerImpl.class, classScope);
        method.getBlock()
                .expression(exprDotMethodChain(symbols.getAddInitSvc(method)).add(EPModuleScriptInitServices.GETSCRIPTCOLLECTOR)
                        .add("registerScript", constant(script.getKey().getName()), constant(script.getKey().getParamNum()), script.getValue().make(method, symbols, classScope)));
        return method;
    }

    private static CodegenMethod registerExprDeclaredCodegen(Map.Entry<String, ExpressionDeclItem> expression, CodegenMethod parent, CodegenClassScope classScope, ModuleExpressionDeclaredInitializeSymbol symbols) {
        CodegenMethod method = parent.makeChild(void.class, EPCompilerImpl.class, classScope);

        ExpressionDeclItem item = expression.getValue();
        byte[] bytes = SerializerUtil.objectToByteArr(item.getOptionalSoda());
        item.setOptionalSodaBytes(() -> bytes);

        method.getBlock()
                .declareVar(ExpressionDeclItem.class, "detail", expression.getValue().make(method, symbols, classScope))
                .expression(exprDotMethodChain(symbols.getAddInitSvc(method)).add(EPModuleExprDeclaredInitServices.GETEXPRDECLAREDCOLLECTOR)
                        .add("registerExprDeclared", constant(expression.getKey()), ref("detail")));
        return method;
    }

    protected static CodegenMethod makeInitEventTypes(CodegenClassScope classScope, ModuleCompileTimeServices compileTimeServices) {
        ModuleEventTypeInitializeSymbol symbolsEventTypeInit = new ModuleEventTypeInitializeSymbol();
        CodegenMethod initializeEventTypesMethod = CodegenMethod.makeParentNode(void.class, EPCompilerImpl.class, symbolsEventTypeInit, classScope).addParam(EPModuleEventTypeInitServices.class, ModuleEventTypeInitializeSymbol.REF_INITSVC.getRef());
        for (EventType eventType : compileTimeServices.getEventTypeCompileTimeRegistry().getNewTypesAdded()) {
            CodegenMethod addType = registerEventTypeCodegen(eventType, initializeEventTypesMethod, classScope, symbolsEventTypeInit);
            initializeEventTypesMethod.getBlock().expression(localMethod(addType));
        }
        return initializeEventTypesMethod;
    }

    private static CodegenMethod registerNamedWindowCodegen(Map.Entry<String, NamedWindowMetaData> namedWindow, CodegenMethodScope parent, CodegenClassScope classScope, ModuleNamedWindowInitializeSymbol symbols) {
        CodegenMethod method = parent.makeChild(void.class, EPCompilerImpl.class, classScope);
        method.getBlock()
                .declareVar(NamedWindowMetaData.class, "detail", namedWindow.getValue().make(symbols.getAddInitSvc(method)))
                .expression(exprDotMethodChain(symbols.getAddInitSvc(method)).add(EPModuleNamedWindowInitServices.GETNAMEDWINDOWCOLLECTOR).add("registerNamedWindow",
                        constant(namedWindow.getKey()), ref("detail")));
        return method;
    }

    private static CodegenMethod registerTableCodegen(Map.Entry<String, TableMetaData> table, CodegenMethodScope parent, CodegenClassScope classScope, ModuleTableInitializeSymbol symbols) {
        CodegenMethod method = parent.makeChild(void.class, EPCompilerImpl.class, classScope);
        method.getBlock()
                .declareVar(TableMetaData.class, "detail", table.getValue().make(parent, symbols, classScope))
                .expression(exprDotMethodChain(symbols.getAddInitSvc(method)).add(EPModuleTableInitServices.GETTABLECOLLECTOR).add("registerTable",
                        constant(table.getKey()), ref("detail")));
        return method;
    }

    private static CodegenMethod registerIndexCodegen(Map.Entry<IndexCompileTimeKey, IndexDetailForge> index, CodegenMethodScope parent, CodegenClassScope classScope, ModuleIndexesInitializeSymbol symbols) {
        CodegenMethod method = parent.makeChild(void.class, EPCompilerImpl.class, classScope);
        method.getBlock()
                .declareVar(IndexCompileTimeKey.class, "key", index.getKey().make(symbols.getAddInitSvc(method)))
                .declareVar(IndexDetail.class, "detail", index.getValue().make(method, symbols, classScope))
                .expression(exprDotMethodChain(symbols.getAddInitSvc(method)).add(EPModuleIndexInitServices.GETINDEXCOLLECTOR)
                        .add("registerIndex", ref("key"), ref("detail")));
        return method;
    }

    private static CodegenMethod registerContextCodegen(Map.Entry<String, ContextMetaData> context, CodegenMethod parent, CodegenClassScope classScope, ModuleContextInitializeSymbol symbols) {
        CodegenMethod method = parent.makeChild(void.class, EPCompilerImpl.class, classScope);
        method.getBlock()
                .declareVar(ContextMetaData.class, "detail", context.getValue().make(symbols.getAddInitSvc(method)))
                .expression(exprDotMethodChain(symbols.getAddInitSvc(method)).add(EPModuleContextInitServices.GETCONTEXTCOLLECTOR)
                        .add("registerContext", constant(context.getKey()), ref("detail")));
        return method;
    }

    private static CodegenMethod registerVariableCodegen(Map.Entry<String, VariableMetaData> variable, CodegenMethodScope parent, CodegenClassScope classScope, ModuleVariableInitializeSymbol symbols) {
        CodegenMethod method = parent.makeChild(void.class, EPCompilerImpl.class, classScope);
        method.getBlock()
                .declareVar(VariableMetaData.class, "detail", variable.getValue().make(symbols.getAddInitSvc(method)))
                .expression(exprDotMethodChain(symbols.getAddInitSvc(method)).add(EPModuleVariableInitServices.GETVARIABLECOLLECTOR)
                        .add("registerVariable", constant(variable.getKey()), ref("detail")));
        return method;
    }

    private static CodegenMethod registerEventTypeCodegen(EventType eventType, CodegenMethodScope parent, CodegenClassScope classScope, ModuleEventTypeInitializeSymbol symbols) {
        CodegenMethod method = parent.makeChild(void.class, EPCompilerImpl.class, classScope);

        // metadata
        method.getBlock().declareVar(EventTypeMetadata.class, "metadata", eventType.getMetadata().toExpression());

        if (eventType instanceof BaseNestableEventType) {
            BaseNestableEventType baseNestable = (BaseNestableEventType) eventType;
            method.getBlock().declareVar(LinkedHashMap.class, "props", localMethod(makePropsCodegen(baseNestable.getTypes(), method, symbols, classScope, () -> baseNestable.getDeepSuperTypes())));
            String registerMethodName = eventType instanceof MapEventType ? "registerMap" : "registerObjectArray";
            String[] superTypeNames = null;
            if (baseNestable.getSuperTypes() != null && baseNestable.getSuperTypes().length > 0) {
                superTypeNames = new String[baseNestable.getSuperTypes().length];
                for (int i = 0; i < baseNestable.getSuperTypes().length; i++) {
                    superTypeNames[i] = baseNestable.getSuperTypes()[i].getName();
                }
            }
            method.getBlock().expression(exprDotMethodChain(symbols.getAddInitSvc(method)).add(EPModuleEventTypeInitServices.GETEVENTTYPECOLLECTOR).add(registerMethodName, ref("metadata"), ref("props"),
                    constant(superTypeNames), constant(baseNestable.getStartTimestampPropertyName()), constant(baseNestable.getEndTimestampPropertyName())));
        } else if (eventType instanceof WrapperEventType) {
            WrapperEventType wrapper = (WrapperEventType) eventType;
            method.getBlock().declareVar(EventType.class, "inner", EventTypeUtility.resolveTypeCodegen(((WrapperEventType) eventType).getUnderlyingEventType(), symbols.getAddInitSvc(method)));
            method.getBlock().declareVar(LinkedHashMap.class, "props", localMethod(makePropsCodegen(wrapper.getUnderlyingMapType().getTypes(), method, symbols, classScope, () -> wrapper.getUnderlyingMapType().getDeepSuperTypes())));
            method.getBlock().expression(exprDotMethodChain(symbols.getAddInitSvc(method)).add(EPModuleEventTypeInitServices.GETEVENTTYPECOLLECTOR).add("registerWrapper", ref("metadata"), ref("inner"), ref("props")));
        } else if (eventType instanceof BeanEventType) {
            BeanEventType beanType = (BeanEventType) eventType;
            CodegenExpression superTypes = makeSupertypes(beanType.getSuperTypes(), symbols.getAddInitSvc(method));
            CodegenExpression deepSuperTypes = makeDeepSupertypes(beanType.getDeepSuperTypesAsSet(), method, symbols, classScope);
            method.getBlock().expression(exprDotMethodChain(symbols.getAddInitSvc(method)).add(EPModuleEventTypeInitServices.GETEVENTTYPECOLLECTOR).add("registerBean", ref("metadata"),
                    constant(beanType.getUnderlyingType()),
                    constant(beanType.getStartTimestampPropertyName()), constant(beanType.getEndTimestampPropertyName()),
                    superTypes, deepSuperTypes));
        } else if (eventType instanceof SchemaXMLEventType) {
            SchemaXMLEventType xmlType = (SchemaXMLEventType) eventType;
            method.getBlock().expression(exprDotMethodChain(symbols.getAddInitSvc(method)).add(EPModuleEventTypeInitServices.GETEVENTTYPECOLLECTOR).add("registerXML", ref("metadata"),
                    constant(xmlType.getRepresentsFragmentOfProperty()), constant(xmlType.getRepresentsOriginalTypeName())));
        } else if (eventType instanceof AvroSchemaEventType) {
            AvroSchemaEventType avroType = (AvroSchemaEventType) eventType;
            method.getBlock().expression(exprDotMethodChain(symbols.getAddInitSvc(method)).add(EPModuleEventTypeInitServices.GETEVENTTYPECOLLECTOR).add("registerAvro", ref("metadata"),
                    constant(avroType.getSchema().toString())));
        } else if (eventType instanceof VariantEventType) {
            VariantEventType variantEventType = (VariantEventType) eventType;
            method.getBlock().expression(exprDotMethodChain(symbols.getAddInitSvc(method)).add(EPModuleEventTypeInitServices.GETEVENTTYPECOLLECTOR).add("registerVariant", ref("metadata"),
                    EventTypeUtility.resolveTypeArrayCodegen(variantEventType.getVariants(), symbols.getAddInitSvc(method)), constant(variantEventType.isVariantAny())));
        } else {
            throw new IllegalStateException("Event type '" + eventType + "' cannot be registered");
        }

        return method;
    }

    private static CodegenExpression makeDeepSupertypes(Set<EventType> deepSuperTypes, CodegenMethodScope parent, ModuleEventTypeInitializeSymbol symbols, CodegenClassScope classScope) {
        if (deepSuperTypes == null || deepSuperTypes.isEmpty()) {
            return staticMethod(Collections.class, "emptySet");
        }
        if (deepSuperTypes.size() == 1) {
            return staticMethod(Collections.class, "singleton", EventTypeUtility.resolveTypeCodegen(deepSuperTypes.iterator().next(), symbols.getAddInitSvc(parent)));
        }
        CodegenMethod method = parent.makeChild(Set.class, CompilerHelperModuleProvider.class, classScope);
        method.getBlock().declareVar(Set.class, "dst", newInstance(LinkedHashSet.class, constant(CollectionUtil.capacityHashMap(deepSuperTypes.size()))));
        for (EventType eventType : deepSuperTypes) {
            method.getBlock().exprDotMethod(ref("dst"), "add", EventTypeUtility.resolveTypeCodegen(eventType, symbols.getAddInitSvc(method)));
        }
        method.getBlock().methodReturn(ref("dst"));
        return localMethod(method);
    }

    private static CodegenExpression makeSupertypes(EventType[] superTypes, CodegenExpressionRef initSvcRef) {
        if (superTypes == null || superTypes.length == 0) {
            return constantNull();
        }
        CodegenExpression[] expressions = new CodegenExpression[superTypes.length];
        for (int i = 0; i < superTypes.length; i++) {
            expressions[i] = EventTypeUtility.resolveTypeCodegen(superTypes[i], initSvcRef);
        }
        return newArrayWithInit(EventType.class, expressions);
    }

    private static CodegenMethod makePropsCodegen(Map<String, Object> types, CodegenMethodScope parent, ModuleEventTypeInitializeSymbol symbols, CodegenClassScope classScope, Supplier<Iterator<EventType>> deepSuperTypes) {
        CodegenMethod method = parent.makeChild(LinkedHashMap.class, CompilerHelperModuleProvider.class, classScope);
        symbols.getAddInitSvc(method);

        method.getBlock().declareVar(LinkedHashMap.class, "props", newInstance(LinkedHashMap.class));
        for (Map.Entry<String, Object> entry : types.entrySet()) {
            boolean propertyOfSupertype = isPropertyOfSupertype(deepSuperTypes, entry.getKey());
            if (propertyOfSupertype) {
                continue;
            }

            Object type = entry.getValue();
            CodegenExpression typeResolver;
            if (type instanceof Class) {
                typeResolver = enumValue((Class) entry.getValue(), "class");
            } else if (type instanceof EventType) {
                EventType innerType = (EventType) type;
                typeResolver = EventTypeUtility.resolveTypeCodegen(innerType, ModuleEventTypeInitializeSymbol.REF_INITSVC);
            } else if (type instanceof EventType[]) {
                EventType[] innerType = (EventType[]) type;
                CodegenExpression typeExpr = EventTypeUtility.resolveTypeCodegen(innerType[0], ModuleEventTypeInitializeSymbol.REF_INITSVC);
                typeResolver = newArrayWithInit(EventType.class, typeExpr);
            } else if (type == null) {
                typeResolver = constantNull();
            } else if (type instanceof TypeBeanOrUnderlying) {
                EventType innerType = ((TypeBeanOrUnderlying) type).getEventType();
                CodegenExpression innerTypeExpr = EventTypeUtility.resolveTypeCodegen(innerType, ModuleEventTypeInitializeSymbol.REF_INITSVC);
                typeResolver = newInstance(TypeBeanOrUnderlying.class, innerTypeExpr);
            } else if (type instanceof TypeBeanOrUnderlying[]) {
                EventType innerType = ((TypeBeanOrUnderlying[]) type)[0].getEventType();
                CodegenExpression innerTypeExpr = EventTypeUtility.resolveTypeCodegen(innerType, ModuleEventTypeInitializeSymbol.REF_INITSVC);
                typeResolver = newArrayWithInit(TypeBeanOrUnderlying.class, newInstance(TypeBeanOrUnderlying.class, innerTypeExpr));
            } else if (type instanceof Map) {
                typeResolver = localMethod(makePropsCodegen((Map<String, Object>) type, parent, symbols, classScope, null));
            } else {
                throw new IllegalStateException("Unrecognized type '" + type + "'");
            }
            method.getBlock().exprDotMethod(ref("props"), "put", constant(entry.getKey()), typeResolver);
        }
        method.getBlock().methodReturn(ref("props"));
        return method;
    }

    private static boolean isPropertyOfSupertype(Supplier<Iterator<EventType>> deepSuperTypes, String key) {
        if (deepSuperTypes == null) {
            return false;
        }
        Iterator<EventType> deepSuperTypesIterator = deepSuperTypes.get();
        while (deepSuperTypesIterator.hasNext()) {
            EventType type = deepSuperTypesIterator.next();
            if (type.isProperty(key)) {
                return true;
            }
        }
        return false;
    }
}