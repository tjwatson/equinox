/*******************************************************************************
 * Copyright (c) 1997-2009 by ProSyst Software GmbH
 * http://www.prosyst.com
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    ProSyst Software GmbH - initial API and implementation
 *******************************************************************************/
package org.eclipse.equinox.internal.ds;

import java.util.*;
import org.eclipse.equinox.internal.ds.impl.ComponentFactoryImpl;
import org.eclipse.equinox.internal.ds.impl.ComponentInstanceImpl;
import org.eclipse.equinox.internal.ds.model.*;
import org.eclipse.osgi.util.NLS;
import org.osgi.framework.*;
import org.osgi.service.cm.Configuration;
import org.osgi.service.component.*;
import org.osgi.service.log.LogService;

/**
 * This class is responsible for creating, tracking and disposing of service
 * instances and registrations.
 * 
 * @author Valentin Valchev
 * @author Stoyan Boshev
 * @author Pavlin Dobrev
 */

public class InstanceProcess {

	public static Resolver resolver;
	public static InstanceProcess staticRef;

	/** map SCP:ServiceRegistration */
	protected Hashtable factoryRegistrations;

	/**
	 * Used with stackCount to handle circular dependencies in the
	 * {@link InstanceProcess#buildComponent(Bundle, ServiceComponentProp, Object)}
	 * method.
	 */
	private Vector delayedBindList;

	//key - the SPC being built;   value - the thread that builds the SCP
	static Hashtable buildingThreads = new Hashtable(7);
	//key - the building thread;   value - Counter - holds the count of entries in buildComponent method  
	static Hashtable stackCounts = new Hashtable(7);
	//specifies the maximum time that a thread must wait for the building thread to complete the building of the SCP
	static int waitTime = Activator.getInteger("equinox.scr.waitTimeOnBlock", 10000); //$NON-NLS-1$

	//key - the SPC;   value - the SCPLock object
	Hashtable scpLocks = new Hashtable(5);

	/**
	 * Handle Instance processing building and disposing.
	 * 
	 * @param resolver
	 *            the resolver instance
	 */
	InstanceProcess(Resolver resolver) {
		InstanceProcess.resolver = resolver;
		factoryRegistrations = new Hashtable(19);
		delayedBindList = new Vector(10);
		staticRef = this;
	}

	/**
	 * dispose cleanup the SCR is shutting down
	 */
	void dispose() {
		factoryRegistrations = null;
	}

	// gets the SCP synch lock to perform some build/release work
	void getLock(ServiceComponentProp scp) {
		SCPLock scpLock;
		synchronized (scp) {
			scpLock = (SCPLock) scpLocks.get(scp);
			if (scpLock == null) {
				scpLock = new SCPLock();
				scpLocks.put(scp, scpLock);
			}
		}
		synchronized (scpLock.lock) {
			Thread currentThread = Thread.currentThread();
			if (!scpLock.busyBuilding) {
				scpLock.busyBuilding = true;
				scpLock.lockCounter++;
				scpLock.workingThread = currentThread;
			} else if (scpLock.workingThread == currentThread) {
				//increase the lock counter - the lock is required recursively
				scpLock.lockCounter++;
			} else if (scpLock.workingThread != currentThread) {
				long start = System.currentTimeMillis();
				long timeToWait = waitTime;
				boolean lockSucceeded = false;
				do {
					try {
						scpLock.lock.wait(timeToWait);
					} catch (InterruptedException e) {
						// do nothing
					}
					if (!scpLock.busyBuilding) {
						scpLock.busyBuilding = true;
						scpLock.lockCounter++;
						scpLock.workingThread = currentThread;
						scpLocks.put(scp, scpLock);
						lockSucceeded = true;
						break;
					}
					timeToWait = waitTime + start - System.currentTimeMillis();
				} while (timeToWait > 0);
				//check if the timeout has passed or the lock is actually successfully held
				if (!lockSucceeded) {
					// The lock is not yet released!
					// Allow the operation but log a warning
					Activator.log(null, LogService.LOG_WARNING, NLS.bind(Messages.TIMEOUT_GETTING_LOCK, Integer.toString(InstanceProcess.waitTime)), new Exception("Debug stacktrace")); //$NON-NLS-1$
				}
			}
		}
	}

	// free the SCP synch lock 
	void freeLock(ServiceComponentProp scp) {
		SCPLock scpLock = (SCPLock) scpLocks.get(scp);
		synchronized (scpLock.lock) {
			if (scpLock.busyBuilding) {
				if (scpLock.workingThread == Thread.currentThread()) {
					//only the thread holding the lock can release it
					scpLock.lockCounter--;
				}
				// release the lock in case the lock counter has decreased to 0
				if (scpLock.lockCounter == 0) {
					scpLock.busyBuilding = false;
					scpLock.workingThread = null;
					scpLock.lock.notify();
					scpLocks.remove(scp);
				}
			}
		}
	}

	/**
	 * Builds the Service Component descriptions (registration of needed
	 * services, building of component instances if necessary (includes
	 * activating and binding)
	 * 
	 * @param list -
	 *            a Vector of all components to build.
	 */
	public void buildComponents(Vector list, boolean security) {
		ServiceComponentProp scp = null;
		ServiceComponent sc;
		String factoryPid = null;

		// loop through SCP list of enabled
		if (list != null) {
			for (int i = 0; i < list.size(); i++) {
				scp = (ServiceComponentProp) list.elementAt(i);
				getLock(scp);
				int componentState = scp.getState();
				if (componentState <= ServiceComponentProp.DISPOSING || componentState > ServiceComponentProp.SATISFIED) {
					//no need to build the component:
					// 1) it is disposed or about to be disposed
					// 2) it is already built or being built
					freeLock(scp);
					continue;
				}
				long start = 0l;
				boolean successfullyBuilt = true;
				try {
					if (Activator.PERF) {
						start = System.currentTimeMillis();
						Activator.log.info("[DS perf] Start building component " + scp); //$NON-NLS-1$
					}
					scp.setState(ServiceComponentProp.BUILDING);
					sc = scp.serviceComponent;
					if (sc.immediate || (sc.factory == null && Activator.INSTANTIATE_ALL)) {
						if (Activator.DEBUG) {
							Activator.log.debug("InstanceProcess.buildComponents(): building immediate component " + scp.name, null); //$NON-NLS-1$
						}
						if (scp.instances.isEmpty()) {
							try {
								buildComponent(null, scp, null, security);
							} catch (Exception e) {
								successfullyBuilt = false;
								if (!(e instanceof ComponentException)) {
									Activator.log(null, LogService.LOG_ERROR, NLS.bind(Messages.CANNOT_BUILD_COMPONENT, scp), e);
								}
							}
						}

						if (sc.serviceInterfaces != null && successfullyBuilt) {
							// this component registers service
							//the service will be registered only if the component was successfully built

							// this will create either plain service component
							// registration
							// or a service factory registration
							registerService(scp, sc.serviceFactory, null);
						}
					} else {

						// ComponentFactory
						if (sc.factory != null) {
							// check if it is NOT a component config created by a
							// component factory
							if (scp.isComponentFactory()) {
								if (Activator.DEBUG) {
									Activator.log.debug("InstanceProcess.buildComponents(): building component factory " + scp.name, null); //$NON-NLS-1$
								}

								// check if MSF
								try {
									Configuration config = Activator.getConfiguration(sc.name);
									if (config != null) {
										factoryPid = config.getFactoryPid();
									}
								} catch (Exception e) {
									Activator.log(null, LogService.LOG_ERROR, NLS.bind(Messages.CANNOT_GET_CONFIGURATION, sc.name), e);
								}

								// if MSF throw exception - can't be
								// ComponentFactory add MSF
								if (factoryPid != null) {
									Vector toDisable = new Vector(1);
									toDisable.addElement(sc);
									InstanceProcess.resolver.disableComponents(toDisable, ComponentConstants.DEACTIVATION_REASON_UNSPECIFIED);
									successfullyBuilt = false;
									throw new org.osgi.service.component.ComponentException(Messages.INCOMPATIBLE_COMBINATION);
								}
								registerComponentFactory(scp);
								// when registering a ComponentFactory we must not
								// register the component configuration as service
								continue;
							}
						}

						// check whether there is a service to register
						if (sc.provides != null) {
							// this will create either plain service component
							// registration or a service factory registration
							registerService(scp, sc.serviceFactory, null);
						}
					}
				} catch (Throwable t) {
					Activator.log(null, LogService.LOG_ERROR, NLS.bind(Messages.EXCEPTION_BUILDING_COMPONENT, scp.serviceComponent), t);
				} finally {
					scp.setState(successfullyBuilt ? ServiceComponentProp.BUILT : ServiceComponentProp.DISPOSED);
					freeLock(scp);
					if (Activator.PERF) {
						start = System.currentTimeMillis() - start;
						Activator.log.info("[DS perf] The component " + scp + " is built for " + Long.toString(start) + "ms"); //$NON-NLS-1$ //$NON-NLS-2$//$NON-NLS-3$
					}
				}
			} // end for
		} // end if (list != null)
	}

	/**
	 * 
	 * Dispose of Component Instances, includes unregistering services and
	 * removing instances.
	 * 
	 * @param scpList -
	 *            list of ComponentDescriptions plus Property objects to be
	 *            disposed
	 */
	void disposeInstances(Vector scpList, int deactivateReason) {
		// loop through SC+P list to be disposed
		if (scpList != null) {
			for (int i = 0; i < scpList.size(); i++) {
				ServiceComponentProp scp = (ServiceComponentProp) scpList.elementAt(i);
				getLock(scp);
				if (scp.getState() <= ServiceComponentProp.DISPOSING) {
					//it is already disposed
					freeLock(scp);
					continue;
				}
				long start = 0l;
				try {
					scp.setState(ServiceComponentProp.DISPOSING);
					if (Activator.PERF) {
						start = System.currentTimeMillis();
						Activator.log.info("[DS perf] Start disposing component " + scp); //$NON-NLS-1$
					}
					disposeInstances(scp, deactivateReason);
				} catch (Throwable t) {
					Activator.log(null, LogService.LOG_ERROR, NLS.bind(Messages.ERROR_DISPOSING_INSTANCES, scp), t);
				} finally {
					resolver.componentDisposed(scp);
					freeLock(scp);
					if (Activator.PERF) {
						start = System.currentTimeMillis() - start;
						Activator.log.info("[DS perf] The component " + scp + " is disposed for " + Long.toString(start) + "ms"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					}
				}
			}
		}
	}

	/**
	 * @param scp
	 */
	private void disposeInstances(ServiceComponentProp scp, int deactivateReason) {
		if (scp.isComponentFactory()) {
			if (Activator.DEBUG) {
				Activator.log.debug("InstanceProcess.disposeInstances(): disposing component factory " + scp.name, null); //$NON-NLS-1$
			}
			ServiceRegistration reg = (ServiceRegistration) factoryRegistrations.remove(scp);
			try {
				if (reg != null)
					reg.unregister();
			} catch (IllegalStateException e) {
				// Service is already unregistered do nothing
				Activator.log(null, LogService.LOG_WARNING, NLS.bind(Messages.FACTORY_REGISTRATION_ALREADY_DISPOSED, scp.name), null);
			}
		}
		ServiceComponent sc = scp.serviceComponent;
		// if no Services provided - dispose of instance immediately
		if (sc.provides == null) {
			if (Activator.DEBUG) {
				Activator.log.debug("InstanceProcess.disposeInstances(): disposing non-provider component " + scp.name, null); //$NON-NLS-1$
			}
			scp.dispose(deactivateReason);
		} else {
			// The component registers services
			if (Activator.DEBUG) {
				Activator.log.debug("InstanceProcess.disposeInstances(): unregistering component " + scp.name, null); //$NON-NLS-1$
			}

			// unregister services if any
			if (scp.registration != null) {
				try {
					ServiceRegistration reg = scp.registration;
					scp.setRegistration(null);
					reg.unregister();
				} catch (IllegalStateException e) {
					// Service is already unregistered do nothing
					Activator.log(null, LogService.LOG_WARNING, NLS.bind(Messages.REGISTRATION_ALREADY_DISPOSED, scp.name), null);
				}
			} else {
				if (Activator.DEBUG) {
					Activator.log.debug("InstanceProcess.disposeInstances(): cannot find registrations for " + scp.name, null); //$NON-NLS-1$
				}
			}
			scp.dispose(deactivateReason);
		}
	}

	/**
	 * Register the Component Factory
	 * 
	 * @param scp
	 */
	private void registerComponentFactory(ServiceComponentProp scp) {
		if (factoryRegistrations.get(scp) != null) {
			//the service factory is already registered
			return;
		}
		ComponentFactory factory = new ComponentFactoryImpl(scp);
		ServiceComponent sc = scp.serviceComponent;
		BundleContext bc = scp.bc;
		// if the factory attribute is set on the component element then
		// register a
		// component factory service
		// for the Service Component on behalf of the Service Component.
		Hashtable properties = new Hashtable(2);
		properties.put(ComponentConstants.COMPONENT_NAME, sc.name);
		properties.put(ComponentConstants.COMPONENT_FACTORY, sc.factory);
		ServiceRegistration reg = bc.registerService(ComponentFactory.class.getName(), factory, properties);
		factoryRegistrations.put(scp, reg);
	}

	/**
	 * Called by dispatcher ( Resolver) when work available on queue
	 * 
	 * @param refList
	 *            Map of ReferenceDescription:subtable Subtable Maps scp:service
	 *            object
	 */
	final void dynamicBind(Vector refList) {
		if (refList == null || refList.isEmpty()) {
			return;
		}
		for (int i = 0; i < refList.size(); i++) {
			Reference ref = (Reference) refList.elementAt(i);
			ServiceComponentProp scp = ref.scp;

			Vector instances = scp.instances;
			if (instances != null) {
				for (int j = 0; j < instances.size(); j++) {
					ComponentInstance compInstance = (ComponentInstance) instances.elementAt(j);
					if (compInstance != null) {
						try {
							scp.bindReference(ref, compInstance);
						} catch (Exception ex) {
							// ex.printStackTrace();
						}
					}
				}
			} else {
				// the component is not used and therefore it is not yet
				// instantiated!
				if (Activator.DEBUG) {
					Activator.log.debug("InstanceProcess.dynamicBind(): null instances for component " + scp.name, null); //$NON-NLS-1$
				}
			}
		}
	}

	/**
	 * Called by dispatcher ( Resolver) when work available on queue
	 * 
	 * @param serviceTable
	 *            Map of ReferenceDescription:subtable Subtable Maps scp:service
	 *            object
	 */
	final void dynamicUnBind(Hashtable serviceTable) {
		try {
			if (serviceTable == null || serviceTable.isEmpty()) {
				return;
			}
			// for each element in the table
			Enumeration e = serviceTable.keys();
			while (e.hasMoreElements()) {
				Reference ref = (Reference) e.nextElement();
				Hashtable serviceSubTable = (Hashtable) serviceTable.get(ref);
				Enumeration sub = serviceSubTable.keys();
				while (sub.hasMoreElements()) {
					ServiceComponentProp scp = (ServiceComponentProp) sub.nextElement();
					ServiceReference serviceReference = (ServiceReference) serviceSubTable.get(scp);
					// get the list of instances created
					Vector instances = scp.instances;
					for (int i = 0; i < instances.size(); i++) {
						ComponentInstance compInstance = (ComponentInstance) instances.elementAt(i);
						if (compInstance != null) {
							try {
								scp.unbindDynamicReference(ref, compInstance, serviceReference);
							} catch (Throwable t) {
								Activator.log(null, LogService.LOG_ERROR, NLS.bind(Messages.ERROR_UNBINDING_REFERENCE, ref.reference, compInstance.getInstance()), t);
							}
						}
					}
				}
			}
		} catch (Throwable e) {
			//should not happen
			Activator.log(null, LogService.LOG_ERROR, Messages.UNEXPECTED_ERROR, e);
		}
	}

	/**
	 * registerService
	 * 
	 * @param scp
	 *            ComponentDescription plus Properties
	 * @param factory
	 *            boolean
	 * @param ci
	 *            the component instance created by ComponentFactoryImpl!
	 */
	private void registerService(ServiceComponentProp scp, boolean factory, ComponentInstanceImpl ci) {
		// register the service using a ServiceFactory
		ServiceRegistration reg = null;
		Object service;
		if (scp.registration != null) {
			//the service has already been registered
			return;
		}
		if (factory) {
			// register as service factory
			service = new FactoryReg(scp);
		} else {
			service = new ServiceReg(scp, ci);
		}

		reg = scp.bc.registerService(scp.serviceComponent.provides, service, scp.getPublicServiceProperties());

		if (Activator.DEBUG) {
			Activator.log.debug("InstanceProcess.registerService(): " + scp.name + " registered as " + ((factory) ? "*factory*" : "*service*"), null); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		}
		if (scp.getState() <= ServiceComponentProp.DISPOSING) {
			//must unregister the service because it was not able to unregister when the component was disposed
			try {
				reg.unregister();
				if (Activator.DEBUG) {
					Activator.log.debug("InstanceProcess.registerService(): The service of component " + scp.name + " was unregistered because the component is already disposed!", null); //$NON-NLS-1$ //$NON-NLS-2$
				}
			} catch (IllegalStateException e) {
				// Service is already unregistered do nothing
			}
		} else {
			scp.setRegistration(reg);
		}
	}

	public ComponentInstanceImpl buildComponent(Bundle usingBundle, ServiceComponentProp scp, Object instance, boolean security) throws ComponentException {
		if (Activator.DEBUG) {
			Activator.log.debug("InstanceProcess.buildComponent(): building component " + scp.name, null); //$NON-NLS-1$
		}
		getLock(scp);
		Counter counter;
		Thread curThread = Thread.currentThread();
		synchronized (scp) {
			Thread theSCPThread = (Thread) buildingThreads.get(scp);
			if (theSCPThread != null && curThread != theSCPThread) { //manage cyclic calls 
				if (scp.isKindOfFactory()) {
					// The scp is a kind of factory - multiple instances are allowed. 
					// The building of the scp is allowed
				} else {
					long start = System.currentTimeMillis();
					long timeToWait = waitTime;
					do {
						try {
							scp.wait(timeToWait);
						} catch (InterruptedException ie) {
							//do nothing
						}
						if (buildingThreads.get(scp) == null) {
							//the lock is released
							break;
						}
						timeToWait = waitTime + start - System.currentTimeMillis();
					} while (timeToWait > 0);

					//check if the timeout has passed or the scp is actually built	
					if (buildingThreads.get(scp) != null) {
						freeLock(scp);
						// The SCP is not yet built
						// We have two options here:
						// 1 - Return the instance (if already created) nevertheless it is not finished its binding and activation phase
						// 2 - throw an exception because something may have gone wrong
						if (!scp.instances.isEmpty()) {
							Activator.log(null, LogService.LOG_WARNING, Messages.RETURNING_NOT_FULLY_ACTIVATED_INSTANCE, new Exception("Debug callstack")); //$NON-NLS-1$
							return (ComponentInstanceImpl) scp.instances.firstElement();
						}

						throw new RuntimeException(NLS.bind(Messages.INSTANCE_CREATION_TOOK_LONGER, scp, Integer.toString(waitTime)));
					}
				}
			}
			buildingThreads.put(scp, curThread);

			// keep track of how many times we have re-entered this method
			counter = (Counter) stackCounts.get(curThread);
			if (counter == null) {
				counter = new Counter();
				stackCounts.put(curThread, counter);
			}
			counter.count++;
		}

		long start = 0l;
		try {
			if (Activator.PERF) {
				start = System.currentTimeMillis();
				Activator.log.info("[DS perf] Start building instance of component " + scp); //$NON-NLS-1$
			}
			ComponentInstanceImpl componentInstance = null;
			try {
				componentInstance = scp.build(usingBundle, instance, security);
			} catch (ComponentException e) {
				Activator.log(null, LogService.LOG_ERROR, e.getMessage(), e.getCause());
				throw e;
			} catch (Throwable t) {
				Activator.log(null, LogService.LOG_ERROR, NLS.bind(Messages.ERROR_BUILDING_COMPONENT_INSTANCE, scp.serviceComponent), t);
				throw new ComponentException(NLS.bind(Messages.ERROR_BUILDING_COMPONENT_INSTANCE, scp.serviceComponent), t);
			} finally {
				// keep track of how many times we have re-entered this method
				counter.count--;
				if (Activator.PERF) {
					start = System.currentTimeMillis() - start;
					Activator.log.info("[DS perf] The instance of component " + scp + " is built for " + Long.toString(start) + "ms"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				}
			}

			// if this is the last time in this method and we have "delayed"
			// bind actions to do (there was a circularity during bind)
			if (counter.count == 0 && !delayedBindList.isEmpty()) {
				// put delayed dynamic binds on the queue.
				// (this is used to handle circularity)
				resolver.mgr.enqueueWork(resolver, Resolver.DYNAMICBIND, delayedBindList.clone(), security);
				delayedBindList.removeAllElements();
			}

			return componentInstance;
		} finally {
			synchronized (scp) {
				if (counter.count == 0) {
					stackCounts.remove(curThread);
				}
				buildingThreads.remove(scp);
				scp.notify();
			}
			freeLock(scp);
		}
	}

	public void modifyComponent(ServiceComponentProp scp, Dictionary newProps) throws ComponentException {
		if (Activator.DEBUG) {
			Activator.log.debug("Modifying component " + scp.name, null); //$NON-NLS-1$
		}
		getLock(scp);
		long start = 0l;
		try {
			if (Activator.PERF) {
				start = System.currentTimeMillis();
				Activator.log.info("[DS perf] Modifying component " + scp.name); //$NON-NLS-1$
			}
			try {
				scp.modify(newProps);
			} catch (ComponentException e) {
				Activator.log(null, LogService.LOG_ERROR, e.getMessage(), e.getCause());
				throw e;
			} catch (Throwable t) {
				Activator.log(null, LogService.LOG_ERROR, NLS.bind(Messages.ERROR_MODIFYING_COMPONENT, scp.serviceComponent), t);
				throw new ComponentException(NLS.bind(Messages.ERROR_MODIFYING_COMPONENT, scp.serviceComponent), t);
			} finally {
				// keep track of how many times we have re-entered this method
				if (Activator.PERF) {
					start = System.currentTimeMillis() - start;
					Activator.log.info("[DS perf] Component " + scp + " modified for " + Long.toString(start) + "ms"); //$NON-NLS-1$//$NON-NLS-2$ //$NON-NLS-3$
				}
			}
		} finally {
			freeLock(scp);
		}
	}

	/**
	 * Acquire a service object from a {@link ServiceReference}.
	 * 
	 * This method checks if "getting" the service could cause a cycle. If so,
	 * it breaks the cycle and returns null.
	 * 
	 * @param reference
	 * @param serviceReference
	 * 
	 * @return the service object or null if it would cause a circularity
	 */
	public Object getService(Reference reference, ServiceReference serviceReference) {
		// check if getting this service would cause a circularity
		if (checkCanCauseCycle(reference, serviceReference)) {
			if (Activator.DEBUG) {
				Activator.log.debug("InstanceProcess.getService(): cannot get service because of circularity! Reference is: " + reference.reference.name + " ; The service reference is " + serviceReference, null); //$NON-NLS-1$//$NON-NLS-2$
			}
			return null;
		}

		// getting this service will not cause a circularity
		Object serviceObject = reference.scp.bc.getService(serviceReference);
		if (serviceObject == null) {
			if (Activator.DEBUG) {
				Activator.log.debug("[SCR] Returned service object by the bundle context is null. The reference is " + reference.reference.name + "; The ServiceReference is " + serviceReference, null); //$NON-NLS-1$ //$NON-NLS-2$
			}
		}
		return serviceObject;
	}

	/**
	 * Check the "cycle list" put in the scp by the resolver to see if getting
	 * this reference would cause a circularity.
	 * 
	 * A circularity is only possible if the "producer" of the service is also a
	 * service component.
	 * 
	 * If getting the service could cause a circularity and the reference's
	 * policy is "dynamic", add an entry to the "delayed bind list" which is
	 * processed when the component is built
	 * 
	 * @param reference
	 * @param serviceReference
	 * @return if getting the service could cause a circularity
	 */
	private boolean checkCanCauseCycle(Reference reference, ServiceReference serviceReference) {

		ServiceComponentProp consumerSCP = reference.scp;
		// if we are not building a component, no cycles possible
		if (buildingThreads.isEmpty()) {
			return false;
		}

		String producerComponentName = (String) serviceReference.getProperty(ComponentConstants.COMPONENT_NAME);

		// if producer is not a service component, no cycles possible
		if (producerComponentName == null) {
			return false;
		}

		// check if producer is the "delayed activate" list
		if (consumerSCP.getDelayActivateSCPNames() == null || !consumerSCP.getDelayActivateSCPNames().contains(producerComponentName)) {
			return false;
		}

		// find producer scp
		ServiceComponentProp producerSCP = null;
		synchronized (resolver.getSyncLock()) {
			for (int i = 0; i < resolver.scpEnabled.size(); i++) {
				ServiceComponentProp scp = (ServiceComponentProp) resolver.scpEnabled.elementAt(i);
				if (producerComponentName.equals(scp.serviceComponent.name)) {
					// found the producer scp
					producerSCP = scp;
					break;
				}
			}
		}

		if (producerSCP != null) {
			if (producerSCP.serviceComponent.serviceFactory) {
				// producer is a service factory - there is a new instance for
				// every
				// bundle, so see if one of the instances is used by this bundle
				if (!producerSCP.instances.isEmpty()) {
					Bundle bundle = consumerSCP.bc.getBundle();
					for (int i = 0; i < producerSCP.instances.size(); i++) {
						ComponentInstanceImpl producerComponentInstance = (ComponentInstanceImpl) producerSCP.instances.elementAt(i);
						if (producerComponentInstance.getComponentContext().getUsingBundle().equals(bundle)) {
							// a producer already exists, so no cycle possible
							return false;
						}
					}
				}
			} else {
				// producer is not a service factory - there will only ever be
				// one
				// instance - if it exists then no cycle possible
				if (!producerSCP.instances.isEmpty()) {
					return false;
				}
			}
		}

		// producer scp is not active - do not activate it because that could
		// cause circularity

		// if reference has bind method and policy=dynamic, activate later and
		// bind
		if (reference.reference.bind != null && reference.policy == ComponentReference.POLICY_DYNAMIC) {
			// delay bind by putting on the queue later
			delayedBindList.addElement(reference);
		}

		// can't get service now because of circularity - we will bind later
		// (dynamically) if the reference had a bind method and was dynamic
		return true;
	}

	/**
	 * Counts re-entry in to the
	 * {@link InstanceProcess#buildComponent(Bundle, ServiceComponentProp, Object)} method. 
	 * This is used to handle circular dependencies.
	 */
	class Counter {
		int count = 0;
	}

	class SCPLock {
		//a flag used for synchronization of build/dispose operations
		boolean busyBuilding = false;
		//the working thread that performs the current build/dispose operation
		Thread workingThread;
		//an object used for synchronization when changing the status of busyBuilding flag
		Object lock = new Object();
		//used to count the number of times a lock is held when required recursively 
		int lockCounter = 0;
	}

}
