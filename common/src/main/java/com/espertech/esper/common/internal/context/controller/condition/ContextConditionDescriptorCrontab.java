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
package com.espertech.esper.common.internal.context.controller.condition;

import com.espertech.esper.common.internal.context.mgr.ContextManagerRealization;
import com.espertech.esper.common.internal.epl.expression.core.ExprEvaluator;
import com.espertech.esper.common.internal.filterspec.FilterSpecActivatable;
import com.espertech.esper.common.internal.schedule.ScheduleComputeHelper;
import com.espertech.esper.common.internal.schedule.ScheduleSpec;
import com.espertech.esper.common.internal.settings.ClasspathImportServiceRuntime;

import java.util.List;

public class ContextConditionDescriptorCrontab implements ContextConditionDescriptor {
    private ExprEvaluator[] evaluators;
    private int scheduleCallbackId = -1;
    private boolean immediate;

    public ExprEvaluator[] getEvaluators() {
        return evaluators;
    }

    public void setEvaluators(ExprEvaluator[] evaluators) {
        this.evaluators = evaluators;
    }

    public int getScheduleCallbackId() {
        return scheduleCallbackId;
    }

    public void setScheduleCallbackId(int scheduleCallbackId) {
        this.scheduleCallbackId = scheduleCallbackId;
    }

    public void addFilterSpecActivatable(List<FilterSpecActivatable> activatables) {
        // none here
    }

    public boolean isImmediate() {
        return immediate;
    }

    public void setImmediate(boolean immediate) {
        this.immediate = immediate;
    }

    public Long getExpectedEndTime(ContextManagerRealization realization, ScheduleSpec scheduleSpec) {
        ClasspathImportServiceRuntime classpathImportService = realization.getAgentInstanceContextCreate().getClasspathImportServiceRuntime();
        return ScheduleComputeHelper.computeNextOccurance(scheduleSpec, realization.getAgentInstanceContextCreate().getTimeProvider().getTime(), classpathImportService.getTimeZone(), classpathImportService.getTimeAbacus());
    }
}
