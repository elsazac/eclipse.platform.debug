/*******************************************************************************
 * Copyright (c) 2000, 2003 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.debug.internal.core;


import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.PlatformObject;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.debug.core.model.IStreamsProxy;


/**
 * A runtime process is a wrapper for a non-debuggable
 * system process. The process will appear in the debug UI with
 * console and termination support. The process creates a streams
 * proxy for itself, and a process monitor that monitors the
 * underlying system process for terminataion.
 */
public class RuntimeProcess extends PlatformObject implements IProcess {

	private static final int MAX_WAIT_FOR_DEATH_ATTEMPTS = 10;
	private static final int TIME_TO_WAIT_FOR_THREAD_DEATH = 500; // ms
	
	/**
	 * The launch this process is contained in
	 */
	private ILaunch fLaunch;
	
	/**
	 * The system process
	 */
	private Process fProcess;
	
	/**
	 * The exit value
	 */
	private int fExitValue;
	
	/**
	 * The monitor which listens for this runtime process' system process
	 * to terminate.
	 */
	private ProcessMonitorJob fMonitor;
	
	/**
	 * The streams proxy for this process
	 */
	private IStreamsProxy fStreamsProxy;

	/**
	 * The name of the process
	 */
	private String fName;

	/**
	 * Whether this process has been terminated
	 */
	private boolean fTerminated;
	
	/**
	 * Table of client defined attributes
	 */
	private Map fAttributes;

	/**
	 * Constructs a RuntimeProcess on the given system process
	 * with the given name, adding this process to the given
	 * launch.
	 */
	public RuntimeProcess(ILaunch launch, Process process, String name, Map attributes) {
		setLaunch(launch);
		fAttributes = attributes;
		fProcess= process;
		fName= name;
		fTerminated= true;
		try {
			process.exitValue();
		} catch (IllegalThreadStateException e) {
			fTerminated= false;
		}
		fStreamsProxy = new StreamsProxy(this);
		fMonitor = new ProcessMonitorJob(this);
		launch.addProcess(this);
		fireCreationEvent();
	}

	/**
	 * @see ITerminate#canTerminate()
	 */
	public boolean canTerminate() {
		return !fTerminated;
	}

	/**
	 * Returns the error stream of the underlying system process (connected
	 * to the standard error of the process).
	 */
	protected InputStream getErrorStream() {
		return fProcess.getErrorStream();
	}

	/**
	 * Returns the input stream of the underlying system process (connected
	 * to the standard out of the process).
	 */
	protected InputStream getInputStream() {
		return fProcess.getInputStream();
	}

	/**
	 * Returns the output stream of the underlying system process (connected
	 * to the standard in of the process).
	 */
	protected OutputStream getOutputStream() {
		return fProcess.getOutputStream();
	}
	
	/**
	 * @see IProcess#getLabel()
	 */
	public String getLabel() {
		return fName;
	}
	
	/**
	 * Sets the launch this process is contained in
	 * 
	 * @param launch the launch this process is contained in
	 */
	private void setLaunch(ILaunch launch) {
		fLaunch = launch;
	}

	/**
	 * @see IProcess#getLaunch()
	 */
	public ILaunch getLaunch() {
		return fLaunch;
	}

	/**
	 * Returns the underlying system process
	 */
	protected Process getSystemProcess() {
		return fProcess;
	}

	/**
	 * @see ITerminate#isTerminated()
	 */
	public boolean isTerminated() {
		return fTerminated;
	}

	/**
	 * @see ITerminate#terminate()
	 */
	public void terminate() throws DebugException {
		if (!isTerminated()) {
			fProcess.destroy();
			if (fStreamsProxy instanceof StreamsProxy) {
				((StreamsProxy)fStreamsProxy).kill();
			}
			int attempts = 0;
			while (attempts < MAX_WAIT_FOR_DEATH_ATTEMPTS) {
				try {
					if (fProcess != null) {
						fExitValue = fProcess.exitValue(); // throws exception if process not exited
					}
					return;
				} catch (IllegalThreadStateException ie) {
				}
				try {
					Thread.sleep(TIME_TO_WAIT_FOR_THREAD_DEATH);
				} catch (InterruptedException e) {
				}
				attempts++;
			}
			// clean-up
			fMonitor.killJob();
			IStatus status = new Status(IStatus.ERROR, DebugPlugin.getUniqueIdentifier(), DebugException.TARGET_REQUEST_FAILED, DebugCoreMessages.getString("RuntimeProcess.terminate_failed"), null);		 //$NON-NLS-1$
			throw new DebugException(status);
		}
	}

	/**
	 * Notification that the system process associated with this process
	 * has terminated.
	 */
	protected void terminated() {
		if (fStreamsProxy instanceof StreamsProxy) {
			((StreamsProxy)fStreamsProxy).close();
		}
		fTerminated= true;
		try {
			fExitValue = fProcess.exitValue();
		} catch (IllegalThreadStateException ie) {
		}
		fProcess= null;
		fireTerminateEvent();
	}
		
	/**
	 * @see IProcess#getStreamsProxy()
	 */
	public IStreamsProxy getStreamsProxy() {
		return fStreamsProxy;
	}
	
	/**
	 * Sets the underlying streams proxy for this process.
	 * Used by the Ant launching in separate VM.
	 */
	public void setStreamsProxy(IStreamsProxy streamsProxy) {
		if (fStreamsProxy instanceof StreamsProxy) {
			((StreamsProxy)fStreamsProxy).kill();
		}
		fStreamsProxy= streamsProxy;
	}
	
	/**
	 * Fire a debug event marking the creation of this element.
	 */
	private void fireCreationEvent() {
		fireEvent(new DebugEvent(this, DebugEvent.CREATE));
	}

	/**
	 * Fire a debug event
	 */
	private void fireEvent(DebugEvent event) {
		DebugPlugin manager= DebugPlugin.getDefault();
		if (manager != null) {
			manager.fireDebugEventSet(new DebugEvent[]{event});
		}
	}

	/**
	 * Fire a debug event marking the termination of this process.
	 */
	private void fireTerminateEvent() {
		fireEvent(new DebugEvent(this, DebugEvent.TERMINATE));
	}

	/**
	 * @see IProcess#setAttribute(String, String)
	 */
	public void setAttribute(String key, String value) {
		if (fAttributes == null) {
			fAttributes = new HashMap(5);
		}
		fAttributes.put(key, value);
	}
	
	/**
	 * @see IProcess#getAttribute(String)
	 */
	public String getAttribute(String key) {
		if (fAttributes == null) {
			return null;
		}
		return (String)fAttributes.get(key);
	}
	
	/**
	 * @see IAdaptable#getAdapter(Class)
	 */
	public Object getAdapter(Class adapter) {
		if (adapter.equals(IProcess.class)) {
			return this;
		}
		if (adapter.equals(IDebugTarget.class)) {
			ILaunch launch = getLaunch();
			IDebugTarget[] targets = launch.getDebugTargets();
			for (int i = 0; i < targets.length; i++) {
				if (this.equals(targets[i].getProcess())) {
					return targets[i];
				}
			}
			return null;
		}
		return super.getAdapter(adapter);
	}
	/**
	 * @see IProcess#getExitValue()
	 */
	public int getExitValue() throws DebugException {
		if (isTerminated()) {
			return fExitValue;
		} else {
			throw new DebugException(new Status(IStatus.ERROR, DebugPlugin.getUniqueIdentifier(), DebugException.TARGET_REQUEST_FAILED, DebugCoreMessages.getString("RuntimeProcess.Exit_value_not_available_until_process_terminates._1"), null)); //$NON-NLS-1$
		}
	}
}