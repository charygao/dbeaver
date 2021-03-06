/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2021 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jkiss.dbeaver.tools.transfer.task;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.runtime.DBRRunnableContext;
import org.jkiss.dbeaver.model.task.DBTTask;
import org.jkiss.dbeaver.model.task.DBTTaskExecutionListener;
import org.jkiss.dbeaver.model.task.DBTTaskHandler;
import org.jkiss.dbeaver.tools.transfer.DataTransferJob;
import org.jkiss.dbeaver.tools.transfer.DataTransferPipe;
import org.jkiss.dbeaver.tools.transfer.DataTransferSettings;
import org.jkiss.dbeaver.tools.transfer.DataTransferState;

import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * DTTaskHandlerTransfer
 */
public class DTTaskHandlerTransfer implements DBTTaskHandler {

    @Override
    public void executeTask(
        @NotNull DBRRunnableContext runnableContext,
        @NotNull DBTTask task,
        @NotNull Locale locale,
        @NotNull Log log,
        @NotNull PrintStream logStream,
        @NotNull DBTTaskExecutionListener listener) throws DBException
    {
        DataTransferSettings[] settings = new DataTransferSettings[1];
        try {
            runnableContext.run(true, true, monitor -> {
                settings[0] = new DataTransferSettings(monitor, task, log, Collections.emptyMap(), new DataTransferState());
            });
        } catch (InvocationTargetException e) {
            throw new DBException("Error loading task settings", e.getTargetException());
        } catch (InterruptedException e) {
            return;
        }
        executeWithSettings(runnableContext, task, locale, log, listener, settings[0]);
    }

    public void executeWithSettings(@NotNull DBRRunnableContext runnableContext, DBTTask task, @NotNull Locale locale, @NotNull Log log, @NotNull DBTTaskExecutionListener listener, DataTransferSettings settings) throws DBException {
        // Start consumers
        listener.taskStarted(settings);

        List<DataTransferPipe> dataPipes = settings.getDataPipes();
        try {
            runnableContext.run(true, false, monitor -> {
                monitor.beginTask("Initialize pipes", dataPipes.size());
                try {
                    for (int i = 0; i < dataPipes.size(); i++) {
                        DataTransferPipe pipe = dataPipes.get(i);
                        pipe.initPipe(settings, i, dataPipes.size());
                        pipe.getConsumer().startTransfer(monitor);
                        monitor.worked(1);
                    }
                } catch (DBException e) {
                    throw new InvocationTargetException(e);
                } finally {
                    monitor.done();
                }
            });
        } catch (InvocationTargetException e) {
            throw new DBException("Error starting data transfer", e.getTargetException());
        } catch (InterruptedException e) {
            return;
        }

        // Schedule jobs for data providers
        int totalJobs = settings.getDataPipes().size();
        if (totalJobs > settings.getMaxJobCount()) {
            totalJobs = settings.getMaxJobCount();
        }
        Throwable error = null;
        for (int i = 0; i < totalJobs; i++) {
            DataTransferJob job = new DataTransferJob(settings, task, locale, log, listener);
            try {
                runnableContext.run(true, true, job);
            } catch (InvocationTargetException e) {
                error = e.getTargetException();
            } catch (InterruptedException e) {
                break;
            }
            listener.subTaskFinished(error);
        }
        listener.taskFinished(settings, error);
    }

}
