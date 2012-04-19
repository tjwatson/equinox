/*******************************************************************************
 * Copyright (c) 2012 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.osgi.container;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.eclipse.osgi.framework.util.ObjectPool;
import org.osgi.framework.Version;
import org.osgi.resource.*;

/**
 * A database for storing modules, their revisions and wiring states.  The
 * database is responsible for assigning ids and providing access to the
 * capabilities provided by the revisions currently installed.
 * <p>
 * This database is not thread safe.  Unless otherwise noted all read and
 * write access must be protected by acquiring the appropriate locks.  
 * All read access must be protected by the {@link #lockRead(boolean)} and 
 * {@link #unlockRead(boolean)} methods.
 * All write access must be protected by the {@link #lockWrite()} and 
 * {@link #unlockWrite()} methods.
 * <p>
 * Unless otherwise noted all public methods of a {@link ModuleDataBase}
 * are considered read operations.
 * The {@link ModuleContainer container} this database is associated with
 * is responsible for accessing the database in a thread safe way.
 */
public abstract class ModuleDataBase {
	/**
	 * The container this database is associated with
	 */
	protected ModuleContainer container = null;

	/**
	 * A map of modules by location.
	 */
	private final Map<String, Module> modulesByLocations;

	/**
	 * A map of modules by id.
	 */
	private final Map<Long, Module> modulesById;

	/**
	 * A map of revision collections by symbolic name
	 */
	private final Map<String, Collection<ModuleRevision>> revisionByName;

	/**
	 * A map of revision wiring objects.
	 */
	private final Map<ModuleRevision, ModuleWiring> wirings;

	/**
	 * Holds the next id to be assigned to a module when it is installed
	 */
	final AtomicLong nextId;

	/**
	 * Holds the current timestamp of this database.
	 */
	final AtomicLong timeStamp;

	/**
	 * Monitors read and write access to this database
	 */
	private final UpgradeableReadWriteLock monitor = new UpgradeableReadWriteLock();

	/**
	 * Constructs a new empty database.
	 */
	public ModuleDataBase() {
		this.modulesByLocations = new HashMap<String, Module>();
		this.modulesById = new HashMap<Long, Module>();
		this.revisionByName = new HashMap<String, Collection<ModuleRevision>>();
		this.wirings = new HashMap<ModuleRevision, ModuleWiring>();
		this.nextId = new AtomicLong(0);
		this.timeStamp = new AtomicLong(0);
	}

	/**
	 * Sets the container for this database.  A database can only
	 * be associated with a single container and that container must
	 * have been constructed with this database.
	 * @param container the container to associate this database with.
	 */
	public final void setContainer(ModuleContainer container) {
		if (this.container != null)
			throw new IllegalStateException("The container is already set."); //$NON-NLS-1$
		if (container.moduleDataBase != this) {
			throw new IllegalArgumentException("Container is already using a different database."); //$NON-NLS-1$
		}
		this.container = container;
	}

	/**
	 * Returns the module at the given location or null if no module exists
	 * at the given location.
	 * @param location the location of the module.
	 * @return the module at the given location or null.
	 */
	final Module getModule(String location) {
		return modulesByLocations.get(location);
	}

	/**
	 * Returns the module at the given id or null if no module exists
	 * at the given location.
	 * @param id the id of the module.
	 * @return the module at the given id or null.
	 */
	final Module getModule(long id) {
		return modulesById.get(id);
	}

	/**
	 * Returns a snapshot collection of revisions with the specified name 
	 * and version.  If version is {@code null} then all revisions with
	 * the specified name are returned.
	 * @param name the name of the modules
	 * @param version the version of the modules or {@code null}
	 * @return a snapshot collection of revisions with the specified name
	 * and version.
	 */
	final Collection<ModuleRevision> getRevisions(String name, Version version) {
		if (version == null)
			return new ArrayList<ModuleRevision>(revisionByName.get(name));

		Collection<ModuleRevision> existingRevisions = revisionByName.get(name);
		if (existingRevisions == null) {
			return Collections.emptyList();
		}
		Collection<ModuleRevision> sameVersion = new ArrayList<ModuleRevision>(1);
		for (ModuleRevision revision : existingRevisions) {
			if (revision.getVersion().equals(version)) {
				sameVersion.add(revision);
			}
		}
		return sameVersion;
	}

	/**
	 * Installs a new revision using the specified builder, location and module
	 * @param location the location to use for the installation
	 * @param builder the builder to use to create the new revision
	 * @return the installed module
	 */
	final Module install(String location, ModuleRevisionBuilder builder) {
		Module module = load(location, builder, getNextIdAndIncrement());
		incrementTimestamp();
		return module;
	}

	final Module load(String location, ModuleRevisionBuilder builder, long id) {
		if (container == null)
			throw new IllegalStateException("Container is not set."); //$NON-NLS-1$
		if (modulesByLocations.get(location) != null)
			throw new IllegalArgumentException("Location is already used."); //$NON-NLS-1$
		Module module = createModule(location, id);
		ModuleRevision newRevision = builder.buildRevision(id, location, module, container);
		modulesByLocations.put(location, module);
		modulesById.put(id, module);
		addToRevisionByName(newRevision);
		addCapabilities(newRevision);
		return module;
	}

	private void addToRevisionByName(ModuleRevision revision) {
		String name = revision.getSymbolicName();
		Collection<ModuleRevision> sameName = revisionByName.get(name);
		if (sameName == null) {
			sameName = new ArrayList<ModuleRevision>(1);
			revisionByName.put(name, sameName);
		}
		sameName.add(revision);
	}

	/**
	 * Uninstalls the specified module from this database.
	 * Uninstalling a module will attempt to clean up any removal pending
	 * revisions possible.
	 * @param module the module to uninstall
	 */
	final void uninstall(Module module) {
		ModuleRevisions uninstalling = module.getRevisions();
		// remove the location
		modulesByLocations.remove(uninstalling.getLocation());
		modulesById.remove(uninstalling.getId());
		// remove the revisions by name
		List<ModuleRevision> revisions = uninstalling.getModuleRevisions();
		for (ModuleRevision revision : revisions) {
			removeCapabilities(revision);
			String name = revision.getSymbolicName();
			if (name != null) {
				Collection<ModuleRevision> sameName = revisionByName.get(name);
				if (sameName != null) {
					sameName.remove(revision);
				}
			}
			// if the revision does not have a wiring it can safely be removed
			// from the revisions for the module
			ModuleWiring oldWiring = wirings.get(revision);
			if (oldWiring == null) {
				module.getRevisions().removeRevision(revision);
			}
		}
		// marke the revisions as uninstalled
		uninstalling.uninstall();
		// attempt to cleanup any removal pendings
		cleanupRemovalPending();

		incrementTimestamp();
	}

	/**
	 * Installs a new revision using the specified builder, location and module
	 * @param module the module for which the revision is being installed for
	 * @param location the location to use for the installation
	 * @param builder the builder to use to create the new revision
	 */
	/**
	 * Updates the specified module with anew revision using the specified builder.
	 * @param module the module for which the revision provides an update for
	 * @param builder the builder to use to create the new revision
	 */
	final void update(Module module, ModuleRevisionBuilder builder) {
		ModuleRevision oldRevision = module.getCurrentRevision();
		ModuleRevision newRevision = builder.addRevision(module.getRevisions());
		String name = newRevision.getSymbolicName();
		Collection<ModuleRevision> sameName = revisionByName.get(name);
		if (sameName == null) {
			sameName = new ArrayList<ModuleRevision>(1);
			revisionByName.put(name, sameName);
		}
		sameName.add(newRevision);
		addCapabilities(newRevision);

		// remove the old revision by name
		String oldName = oldRevision.getSymbolicName();
		if (oldName != null) {
			Collection<ModuleRevision> oldSameName = revisionByName.get(oldName);
			if (oldSameName != null) {
				oldSameName.remove(oldRevision);
			}
		}

		// if the old revision does not have a wiring it can safely be removed
		ModuleWiring oldWiring = wirings.get(oldRevision);
		if (oldWiring == null) {
			module.getRevisions().removeRevision(oldRevision);
			removeCapabilities(oldRevision);
		}
		// attempt to clean up removal pendings
		cleanupRemovalPending();

		incrementTimestamp();
	}

	/**
	 * Examines the wirings to determine if there are any removal
	 * pending wiring objects that can be removed.  We consider
	 * a removal pending wiring as removable if all dependent
	 * wiring are also removal pending.
	 */
	private void cleanupRemovalPending() {
		Collection<ModuleRevision> removalPending = getRemovalPending();
		for (ModuleRevision removed : removalPending) {
			if (wirings.get(removed) == null)
				continue;
			Collection<ModuleRevision> dependencyClosure = ModuleContainer.getDependencyClosure(removed, wirings);
			boolean allPendingRemoval = true;
			for (ModuleRevision pendingRemoval : dependencyClosure) {
				if (pendingRemoval.isCurrent()) {
					allPendingRemoval = false;
					break;
				}
			}
			if (allPendingRemoval) {
				for (ModuleRevision pendingRemoval : dependencyClosure) {
					pendingRemoval.getRevisions().removeRevision(pendingRemoval);
					removeCapabilities(pendingRemoval);
					wirings.remove(pendingRemoval);
				}
			}
		}
	}

	/**
	 * Gets all revisions with a removal pending wiring.
	 * @return all revisions with a removal pending wiring.
	 */
	final Collection<ModuleRevision> getRemovalPending() {
		Collection<ModuleRevision> removalPending = new ArrayList<ModuleRevision>();
		for (ModuleWiring wiring : wirings.values()) {
			if (!wiring.isCurrent())
				removalPending.add(wiring.getRevision());
		}
		return removalPending;
	}

	/**
	 * Returns the current wiring for the specified revision or
	 * null of no wiring exists for the revision.
	 * @param revision the revision to get the wiring for
	 * @return the current wiring for the specified revision.
	 */
	final ModuleWiring getWiring(ModuleRevision revision) {
		return wirings.get(revision);
	}

	/**
	 * Returns a snapshot of the wirings for all revisions.
	 * @return a snapshot of the wirings for all revisions.
	 */
	final Map<ModuleRevision, ModuleWiring> getWiringsCopy() {
		return new HashMap<ModuleRevision, ModuleWiring>(wirings);
	}

	/**
	 * Replaces the complete wiring map with the specified wiring
	 * @param newWiring the new wiring to take effect.  The values
	 * from the new wiring are copied.
	 */
	final void setWiring(Map<ModuleRevision, ModuleWiring> newWiring) {
		wirings.clear();
		wirings.putAll(newWiring);
		incrementTimestamp();
	}

	/**
	 * Adds all the values from the specified delta wirings to the
	 * wirings current wirings
	 * @param deltaWiring the new wiring values to take effect.
	 * The values from the delta wiring are copied.
	 */
	final void mergeWiring(Map<ModuleRevision, ModuleWiring> deltaWiring) {
		wirings.putAll(deltaWiring);
		incrementTimestamp();
	}

	/**
	 * Returns a snapshot of all modules.
	 * @return a snapshot of all modules.
	 */
	protected final List<Module> getModules() {
		List<Module> modules = new ArrayList<Module>(modulesByLocations.values());
		Collections.sort(modules, new Comparator<Module>() {
			@Override
			public int compare(Module m1, Module m2) {
				return m1.getRevisions().getId().compareTo(m2.getRevisions().getId());
			}
		});
		return modules;
	}

	/**
	 * Increments by one the next module ID
	 * @return the previous module ID
	 */
	private long getNextIdAndIncrement() {
		return nextId.getAndIncrement();
	}

	public final long getNextId() {
		return nextId.get();
	}

	/**
	 * Returns the current timestamp of this database.
	 * The timestamp is incremented any time a modification
	 * is made to this database.  For example:
	 * <ul>
	 *   <li> installing a module
	 *   <li> updating a module
	 *   <li> uninstalling a module
	 *   <li> modifying the wirings
	 * </ul>
	 * @return the current timestamp of this database.
	 */
	final public long getTimestamp() {
		return timeStamp.get();
	}

	/**
	 * Increments the timestamp of this database.
	 */
	private void incrementTimestamp() {
		timeStamp.incrementAndGet();
	}

	/**
	 * @return the number of read holds the current thread has.
	 * @see ReentrantReadWriteLock#getReadHoldCount()
	 */
	final int getReadHoldCount() {
		return monitor.getReadHoldCount();
	}

	/**
	 * @param reserveUpgrade
	 * @see UpgradeableReadWriteLock#lockRead(boolean)
	 */
	public final void lockRead(boolean reserveUpgrade) {
		monitor.lockRead(reserveUpgrade);
	}

	/**
	 * @see UpgradeableReadWriteLock#lockWrite()
	 */
	public final void lockWrite() {
		monitor.lockWrite();
	}

	/**
	 * 
	 * @param unreserveUpgrade
	 * @see UpgradeableReadWriteLock#unlockRead(boolean)
	 */
	public final void unlockRead(boolean unreserveUpgrade) {
		monitor.unlockRead(unreserveUpgrade);
	}

	/**
	 * @see UpgradeableReadWriteLock#unlockWrite()
	 */
	public final void unlockWrite() {
		monitor.unlockWrite();
	}

	/**
	 * Adds the {@link ModuleRevision#getModuleCapabilities(String) capabilities}
	 * provided by the specified revision to this database.  These capabilities must 
	 * become available for lookup with the {@link ModuleDataBase#findCapabilities(ModuleRequirement)}
	 * method.
	 * @param revision the revision which has capabilities to add
	 */
	protected abstract void addCapabilities(ModuleRevision revision);

	/**
	 * Removes the {@link ModuleRevision#getModuleCapabilities(String) capabilities}
	 * provided by the specified revision from this database.  These capabilities
	 * must no longer be available for lookup with the 
	 * {@link ModuleDataBase#findCapabilities(ModuleRequirement)} method.
	 * @param revision
	 */
	protected abstract void removeCapabilities(ModuleRevision revision);

	/**
	 * Returns a mutable snapshot of capabilities that are candidates for 
	 * satisfying the specified requirement.
	 * @param requirement the requirement
	 * @return the candidates for the requirement
	 */
	protected abstract List<ModuleCapability> findCapabilities(ModuleRequirement requirement);

	/**
	 * Creates a new module.  This gets called when a new module is installed.
	 * @param location the location for the module
	 * @param id the id for the module
	 * @return the Module
	 */
	protected abstract Module createModule(String location, long id);

	/**
	 * Writes this database in a format suitable for using the {@link #load(DataInputStream)}
	 * method.  All modules are stored which have a current {@link ModuleRevision revision}.
	 * Only the current revision of each module is stored (no removal pending revisions
	 * are stored).  Optionally the {@link ModuleWiring wiring} of each current revision 
	 * may be stored.  Wiring can only be stored if there are no {@link #getRemovalPending()
	 * removal pending} revisions.
	 * <p>
	 * This method acquires the {@link #lockRead(boolean) read} lock while writing this
	 * database.
	 * <p>
	 * After this database have been written, the output stream is flushed.  
	 * The output stream remains open after this method returns.
	 * @param out the data output steam.
	 * @param persistWirings true if wirings should be persisted.  This option will be ignored
	 *        if there are {@link #getRemovalPending() removal pending} revisions.
	 * @throws IOException if writing this database to the specified output stream throws an IOException
	 */
	public final void store(DataOutputStream out, boolean persistWirings) throws IOException {
		lockRead(false);
		try {
			Persistence.store(this, out, persistWirings);
		} finally {
			unlockRead(false);
		}
	}

	/**
	 * Loads information into this database from the input data stream.  This data
	 * base must be empty and never been modified (the {@link #getTimestamp() timestamp} is zero.
	 * All stored modules are loaded into this database.  If the input stream contains
	 * wiring then it will also be loaded into this database.
	 * <p>
	 * Since this method modifies this database it is considered a write operation.
	 * This method acquires the {@link #lockWrite() write} lock while loading
	 * the information into this database.
	 * <p>
	 * The specified stream remains open after this method returns.
	 * @param in the data input stream.
	 * @throws IOException if an error occurred when reading from the input stream.
	 * @throws IllegalStateException if this database is not empty.
	 */
	public final void load(DataInputStream in) throws IOException {
		lockWrite();
		try {
			if (timeStamp.get() != 0)
				throw new IllegalStateException("Can only load into a empty database."); //$NON-NLS-1$
			Persistence.load(this, in);
		} finally {
			unlockWrite();
		}
	}

	private static class Persistence {
		private static final int VERSION = 1;
		private static final byte NULL = 0;
		private static final byte OBJECT = 1;
		private static final byte LONG_STRING = 3;
		private static final String UTF_8 = "UTF-8"; //$NON-NLS-1$

		private static final byte VALUE_STRING = 0;
		private static final byte VALUE_STRING_ARRAY = 1;
		private static final byte VAlUE_BOOLEAN = 2;
		private static final byte VALUE_INTEGER = 3;
		private static final byte VALUE_LONG = 4;
		private static final byte VALUE_DOUBLE = 5;
		private static final byte VALUE_VERSION = 6;
		private static final byte VALUE_URI = 7;
		private static final byte VALUE_LIST = 8;

		private static int addToWriteTable(Object object, Map<Object, Integer> objectTable) {
			if (object == null)
				throw new NullPointerException();
			Integer cur = objectTable.get(object);
			if (cur != null)
				throw new IllegalStateException("Object is already in the write table: " + object); //$NON-NLS-1$
			objectTable.put(object, new Integer(objectTable.size()));
			// return the index of the object just added (i.e. size - 1)
			return (objectTable.size() - 1);
		}

		private static void addToReadTable(Object object, int index, Map<Integer, Object> objectTable) {
			objectTable.put(new Integer(index), object);
		}

		public static void store(ModuleDataBase moduleDataBase, DataOutputStream out, boolean persistWirings) throws IOException {
			out.writeInt(VERSION);
			out.writeLong(moduleDataBase.getTimestamp());
			out.writeLong(moduleDataBase.getNextId());
			List<Module> modules = moduleDataBase.getModules();
			out.writeInt(modules.size());

			Map<Object, Integer> objectTable = new HashMap<Object, Integer>();
			for (Module module : modules) {
				writeModule(module, out, objectTable);
			}

			Collection<ModuleRevision> removalPendings = moduleDataBase.getRemovalPending();
			// only persist wirings if there are no removals pending
			persistWirings &= removalPendings.isEmpty();
			out.writeBoolean(persistWirings);
			if (!persistWirings) {
				return;
			}

			Map<ModuleRevision, ModuleWiring> wirings = moduleDataBase.getWiringsCopy();
			// prime the object table with all the required wires
			out.writeInt(wirings.size());
			for (ModuleWiring wiring : wirings.values()) {
				List<ModuleWire> requiredWires = wiring.getRequiredModuleWires(null);
				out.writeInt(requiredWires.size());
				for (ModuleWire wire : requiredWires) {
					writeWire(wire, out, objectTable);
				}
			}

			// now write all the info about each wiring using only indexes
			for (ModuleWiring wiring : wirings.values()) {
				writeWiring(wiring, out, objectTable);
			}

			out.flush();
		}

		public static void load(ModuleDataBase moduleDataBase, DataInputStream in) throws IOException {
			int version = in.readInt();
			if (version < VERSION)
				throw new UnsupportedOperationException("Perstence version is not correct for loading: " + version + " expecting: " + VERSION); //$NON-NLS-1$ //$NON-NLS-2$
			moduleDataBase.timeStamp.set(in.readLong());
			moduleDataBase.nextId.set(in.readLong());
			int numModules = in.readInt();

			Map<Integer, Object> objectTable = new HashMap<Integer, Object>();
			for (int i = 0; i < numModules; i++) {
				readModule(moduleDataBase, in, objectTable);
			}
			if (!in.readBoolean())
				return; // no wires persisted

			int numWirings = in.readInt();
			// prime the table with all the required wires
			for (int i = 0; i < numWirings; i++) {
				int numWires = in.readInt();
				for (int j = 0; j < numWires; j++) {
					readWire(in, objectTable);
				}
			}

			// now read all the info about each wiring using only indexes
			Map<ModuleRevision, ModuleWiring> wirings = new HashMap<ModuleRevision, ModuleWiring>();
			for (int i = 0; i < numWirings; i++) {
				ModuleWiring wiring = readWiring(in, objectTable);
				wirings.put(wiring.getRevision(), wiring);
			}
			// TODO need to do this without incrementing the timestamp
			moduleDataBase.setWiring(wirings);
		}

		private static void writeModule(Module module, DataOutputStream out, Map<Object, Integer> objectTable) throws IOException {
			ModuleRevision current = module.getCurrentRevision();
			if (current == null)
				return;
			out.writeInt(addToWriteTable(current, objectTable));

			ModuleRevisions revisions = module.getRevisions();

			writeString(revisions.getLocation(), out);
			out.writeLong(revisions.getId());

			writeString(current.getSymbolicName(), out);
			writeVersion(current.getVersion(), out);
			out.writeInt(current.getTypes());

			List<Capability> capabilities = current.getCapabilities(null);
			out.writeInt(capabilities.size());
			for (Capability capability : capabilities) {
				out.writeInt(addToWriteTable(capability, objectTable));
				writeGenericInfo(capability.getNamespace(), capability.getAttributes(), capability.getDirectives(), out);
			}

			List<Requirement> requirements = current.getRequirements(null);
			out.writeInt(requirements.size());
			for (Requirement requirement : requirements) {
				out.writeInt(addToWriteTable(requirement, objectTable));
				writeGenericInfo(requirement.getNamespace(), requirement.getAttributes(), requirement.getDirectives(), out);
			}
		}

		private static void readModule(ModuleDataBase moduleDataBase, DataInputStream in, Map<Integer, Object> objectTable) throws IOException {
			ModuleRevisionBuilder builder = new ModuleRevisionBuilder();
			int moduleIndex = in.readInt();
			String location = readString(in);
			long id = in.readLong();
			builder.setSymbolicName(readString(in));
			builder.setVersion(readVersion(in));
			builder.setTypes(in.readInt());

			int numCapabilities = in.readInt();
			int[] capabilityIndexes = new int[numCapabilities];
			for (int i = 0; i < numCapabilities; i++) {
				capabilityIndexes[i] = in.readInt();
				readGenericInfo(true, in, builder);
			}

			int numRequirements = in.readInt();
			int[] requirementIndexes = new int[numRequirements];
			for (int i = 0; i < numRequirements; i++) {
				requirementIndexes[i] = in.readInt();
				readGenericInfo(false, in, builder);
			}
			Module module = moduleDataBase.load(location, builder, id);

			ModuleRevision current = module.getCurrentRevision();
			addToReadTable(current, moduleIndex, objectTable);

			List<ModuleCapability> capabilities = current.getModuleCapabilities(null);
			for (int i = 0; i < capabilities.size(); i++) {
				addToReadTable(capabilities.get(i), capabilityIndexes[i], objectTable);
			}

			List<ModuleRequirement> requirements = current.getModuleRequirements(null);
			for (int i = 0; i < requirements.size(); i++) {
				addToReadTable(requirements.get(i), requirementIndexes[i], objectTable);
			}
		}

		private static void writeWire(ModuleWire wire, DataOutputStream out, Map<Object, Integer> objectTable) throws IOException {
			Wire w = wire;
			Integer capability = objectTable.get(w.getCapability());
			Integer provider = objectTable.get(w.getProvider());
			Integer requirement = objectTable.get(w.getRequirement());
			Integer requirer = objectTable.get(w.getRequirer());

			if (capability == null || provider == null || requirement == null || requirer == null)
				throw new NullPointerException("Could not find the expected indexes"); //$NON-NLS-1$

			out.writeInt(addToWriteTable(wire, objectTable));

			out.writeInt(capability);
			out.writeInt(provider);
			out.writeInt(requirement);
			out.writeInt(requirer);
		}

		private static void readWire(DataInputStream in, Map<Integer, Object> objectTable) throws IOException {
			int wireIndex = in.readInt();

			ModuleCapability capability = (ModuleCapability) objectTable.get(in.readInt());
			ModuleRevision provider = (ModuleRevision) objectTable.get(in.readInt());
			ModuleRequirement requirement = (ModuleRequirement) objectTable.get(in.readInt());
			ModuleRevision requirer = (ModuleRevision) objectTable.get(in.readInt());

			if (capability == null || provider == null || requirement == null || requirer == null)
				throw new NullPointerException("Could not find the expected indexes"); //$NON-NLS-1$

			ModuleWire result = new ModuleWire(capability, provider, requirement, requirer);

			addToReadTable(result, wireIndex, objectTable);
		}

		private static void writeWiring(ModuleWiring wiring, DataOutputStream out, Map<Object, Integer> objectTable) throws IOException {
			Integer revisionIndex = objectTable.get(wiring.getRevision());
			if (revisionIndex == null)
				throw new NullPointerException("Could not find revision for wiring."); //$NON-NLS-1$
			out.writeInt(revisionIndex);

			List<ModuleCapability> capabilities = wiring.getModuleCapabilities(null);
			out.writeInt(capabilities.size());
			for (ModuleCapability capability : capabilities) {
				Integer capabilityIndex = objectTable.get(capability);
				if (capabilityIndex == null)
					throw new NullPointerException("Could not find capability for wiring."); //$NON-NLS-1$
				out.writeInt(capabilityIndex);
			}

			List<ModuleRequirement> requirements = wiring.getModuleRequirements(null);
			out.writeInt(requirements.size());
			for (ModuleRequirement requirement : requirements) {
				Integer requirementIndex = objectTable.get(requirement);
				if (requirementIndex == null)
					throw new NullPointerException("Could not find requirement for wiring."); //$NON-NLS-1$
				out.writeInt(requirementIndex);
			}

			List<ModuleWire> providedWires = wiring.getProvidedModuleWires(null);
			out.writeInt(providedWires.size());
			for (ModuleWire wire : providedWires) {
				Integer wireIndex = objectTable.get(wire);
				if (wireIndex == null)
					throw new NullPointerException("Could not find provided wire for wiring."); //$NON-NLS-1$
				out.writeInt(wireIndex);
			}

			List<ModuleWire> requiredWires = wiring.getRequiredModuleWires(null);
			out.writeInt(requiredWires.size());
			for (ModuleWire wire : requiredWires) {
				Integer wireIndex = objectTable.get(wire);
				if (wireIndex == null)
					throw new NullPointerException("Could not find required wire for wiring."); //$NON-NLS-1$
				out.writeInt(wireIndex);
			}
		}

		private static ModuleWiring readWiring(DataInputStream in, Map<Integer, Object> objectTable) throws IOException {
			ModuleRevision revision = (ModuleRevision) objectTable.get(in.readInt());
			if (revision == null)
				throw new NullPointerException("Could not find revision for wiring."); //$NON-NLS-1$

			int numCapabilities = in.readInt();
			List<ModuleCapability> capabilities = new ArrayList<ModuleCapability>(numCapabilities);
			for (int i = 0; i < numCapabilities; i++) {
				capabilities.add((ModuleCapability) objectTable.get(in.readInt()));
			}

			int numRequirements = in.readInt();
			List<ModuleRequirement> requirements = new ArrayList<ModuleRequirement>(numRequirements);
			for (int i = 0; i < numRequirements; i++) {
				requirements.add((ModuleRequirement) objectTable.get(in.readInt()));
			}

			int numProvidedWires = in.readInt();
			List<ModuleWire> providedWires = new ArrayList<ModuleWire>(numProvidedWires);
			for (int i = 0; i < numProvidedWires; i++) {
				providedWires.add((ModuleWire) objectTable.get(in.readInt()));
			}

			int numRequiredWires = in.readInt();
			List<ModuleWire> requiredWires = new ArrayList<ModuleWire>(numRequiredWires);
			for (int i = 0; i < numRequiredWires; i++) {
				requiredWires.add((ModuleWire) objectTable.get(in.readInt()));
			}

			return new ModuleWiring(revision, capabilities, requirements, providedWires, requiredWires);
		}

		private static void writeGenericInfo(String namespace, Map<String, ?> attributes, Map<String, String> directives, DataOutputStream out) throws IOException {
			writeString(namespace, out);
			writeMap(attributes, out);
			writeMap(directives, out);
		}

		@SuppressWarnings("unchecked")
		private static void readGenericInfo(boolean isCapability, DataInputStream in, ModuleRevisionBuilder builder) throws IOException {
			String namespace = readString(in);
			Map<String, Object> attributes = readMap(in);
			Map<String, ?> directives = readMap(in);
			if (isCapability) {
				builder.addCapability(namespace, (Map<String, String>) directives, attributes);
			} else {
				builder.addRequirement(namespace, (Map<String, String>) directives, attributes);
			}

		}

		private static void writeMap(Map<String, ?> source, DataOutputStream out) throws IOException {
			if (source == null) {
				out.writeInt(0);
			} else {
				out.writeInt(source.size());
				Iterator<String> iter = source.keySet().iterator();
				while (iter.hasNext()) {
					String key = iter.next();
					Object value = source.get(key);
					writeString(key, out);
					if (value instanceof String) {
						out.writeByte(VALUE_STRING);
						writeString((String) value, out);
					} else if (value instanceof String[]) {
						out.writeByte(VALUE_STRING_ARRAY);
						writeStringArray(out, (String[]) value);
					} else if (value instanceof Boolean) {
						out.writeByte(VAlUE_BOOLEAN);
						out.writeBoolean(((Boolean) value).booleanValue());
					} else if (value instanceof Integer) {
						out.writeByte(VALUE_INTEGER);
						out.writeInt(((Integer) value).intValue());
					} else if (value instanceof Long) {
						out.writeByte(VALUE_LONG);
						out.writeLong(((Long) value).longValue());
					} else if (value instanceof Double) {
						out.writeByte(VALUE_DOUBLE);
						out.writeDouble(((Double) value).doubleValue());
					} else if (value instanceof Version) {
						out.writeByte(VALUE_VERSION);
						writeVersion((Version) value, out);
					} else if (value instanceof URI) {
						out.writeByte(VALUE_URI);
						writeString(value.toString(), out);
					} else if (value instanceof List) {
						out.writeByte(VALUE_LIST);
						writeList(out, (List<?>) value);
					}
				}
			}
		}

		private static Map<String, Object> readMap(DataInputStream in) throws IOException {
			int count = in.readInt();
			HashMap<String, Object> result = new HashMap<String, Object>(count);
			for (int i = 0; i < count; i++) {
				String key = readString(in);
				Object value = null;
				byte type = in.readByte();
				if (type == VALUE_STRING)
					value = readString(in);
				else if (type == VALUE_STRING_ARRAY)
					value = readStringArray(in);
				else if (type == VAlUE_BOOLEAN)
					value = in.readBoolean() ? Boolean.TRUE : Boolean.FALSE;
				else if (type == VALUE_INTEGER)
					value = new Integer(in.readInt());
				else if (type == VALUE_LONG)
					value = new Long(in.readLong());
				else if (type == VALUE_DOUBLE)
					value = new Double(in.readDouble());
				else if (type == VALUE_VERSION)
					value = readVersion(in);
				else if (type == VALUE_URI)
					try {
						value = new URI(readString(in));
					} catch (URISyntaxException e) {
						value = null;
					}
				else if (type == VALUE_LIST)
					value = readList(in);

				result.put(key, value);
			}
			return result;
		}

		private static void writeStringArray(DataOutputStream out, String[] value) throws IOException {
			if (value == null) {
				out.writeInt(0);
			} else {
				out.writeInt(value.length);
				for (int i = 0; i < value.length; i++)
					writeString(value[i], out);
			}

		}

		private static String[] readStringArray(DataInputStream in) throws IOException {
			int count = in.readInt();
			if (count == 0)
				return null;
			String[] result = new String[count];
			for (int i = 0; i < count; i++)
				result[i] = readString(in);
			return result;
		}

		private static void writeList(DataOutputStream out, List<?> list) throws IOException {
			if (list.isEmpty()) {
				out.writeInt(0);
				return;
			}
			byte type = getListType(list);
			if (type < 0) {
				out.writeInt(0);
				return; // don't understand the list type
			}
			out.writeInt(list.size());
			out.writeByte(type);
			for (Object value : list) {
				switch (type) {
					case VALUE_STRING :
						writeString((String) value, out);
						break;
					case VALUE_INTEGER :
						out.writeInt(((Integer) value).intValue());
						break;
					case VALUE_LONG :
						out.writeLong(((Long) value).longValue());
						break;
					case VALUE_DOUBLE :
						out.writeDouble(((Double) value).doubleValue());
						break;
					case VALUE_VERSION :
						writeVersion((Version) value, out);
						break;
					default :
						break;
				}
			}
		}

		private static byte getListType(List<?> list) {
			if (list.size() == 0)
				return -1;
			Object type = list.get(0);
			if (type instanceof String)
				return VALUE_STRING;
			if (type instanceof Integer)
				return VALUE_INTEGER;
			if (type instanceof Long)
				return VALUE_LONG;
			if (type instanceof Double)
				return VALUE_DOUBLE;
			if (type instanceof Version)
				return VALUE_VERSION;
			return -2;
		}

		private static List<?> readList(DataInputStream in) throws IOException {

			int size = in.readInt();
			if (size == 0)
				return new ArrayList<Object>(0);
			byte listType = in.readByte();
			List<Object> list = new ArrayList<Object>(size);
			for (int i = 0; i < size; i++) {
				switch (listType) {
					case VALUE_STRING :
						list.add(readString(in));
						break;
					case VALUE_INTEGER :
						list.add(new Integer(in.readInt()));
						break;
					case VALUE_LONG :
						list.add(new Long(in.readLong()));
						break;
					case VALUE_DOUBLE :
						list.add(new Double(in.readDouble()));
						break;
					case VALUE_VERSION :
						list.add(readVersion(in));
						break;
					default :
						throw new IOException("Invalid type: " + listType); //$NON-NLS-1$
				}
			}
			return list;
		}

		private static void writeVersion(Version version, DataOutputStream out) throws IOException {
			if (version == null || version.equals(Version.emptyVersion)) {
				out.writeByte(NULL);
				return;
			}
			out.writeByte(OBJECT);
			out.writeInt(version.getMajor());
			out.writeInt(version.getMinor());
			out.writeInt(version.getMicro());
			writeQualifier(version.getQualifier(), out);
		}

		private static void writeQualifier(String string, DataOutputStream out) throws IOException {
			if (string != null && string.length() == 0)
				string = null;
			writeString(string, out);
		}

		private static Version readVersion(DataInputStream in) throws IOException {
			byte tag = in.readByte();
			if (tag == NULL)
				return Version.emptyVersion;
			int majorComponent = in.readInt();
			int minorComponent = in.readInt();
			int serviceComponent = in.readInt();
			String qualifierComponent = readString(in);
			return (Version) ObjectPool.intern(new Version(majorComponent, minorComponent, serviceComponent, qualifierComponent));
		}

		private static void writeString(String string, DataOutputStream out) throws IOException {
			if (string == null)
				out.writeByte(NULL);
			else {
				byte[] data = string.getBytes(UTF_8);

				if (data.length > 65535) {
					out.writeByte(LONG_STRING);
					out.writeInt(data.length);
					out.write(data);
				} else {
					out.writeByte(OBJECT);
					out.writeUTF(string);
				}
			}
		}

		static private String readString(DataInputStream in) throws IOException {
			byte type = in.readByte();
			if (type == NULL)
				return null;

			if (type == LONG_STRING) {
				int length = in.readInt();
				byte[] data = new byte[length];
				in.readFully(data);
				String string = new String(data, UTF_8);

				return (String) ObjectPool.intern(string);
			}

			return (String) ObjectPool.intern(in.readUTF());
		}
	}
}
