package i5.las2peer.p2p;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import i5.las2peer.api.p2p.ServiceNameVersion;
import i5.las2peer.api.p2p.ServiceVersion;
import i5.las2peer.communication.Message;
import i5.las2peer.communication.ServiceDiscoveryContent;
import i5.las2peer.logging.L2pLogger;
import i5.las2peer.persistency.EncodingFailedException;
import i5.las2peer.security.AgentImpl;
import i5.las2peer.security.InternalSecurityException;
import i5.las2peer.security.ServiceAgentImpl;
import i5.las2peer.serialization.SerializationException;

/**
 * Caches the knowledge about existing services
 */
public class NodeServiceCache {

	private final L2pLogger logger = L2pLogger.getInstance(NodeServiceCache.class);

	private final Node runningAt;

	private final Map<String, SortedMap<ServiceVersion, ServiceInstance>> localServices = new HashMap<>();
	private final Map<String, SortedMap<ServiceVersion, SortedSet<ServiceInstance>>> globalServices = new HashMap<>();

	private long lifeTimeSeconds;
	private int waitForResults;
	private int timeoutMs = 2000;

	public NodeServiceCache(Node parent, long lifeTime, int resultCount) {
		this.runningAt = parent;
		this.lifeTimeSeconds = lifeTime;
		this.waitForResults = resultCount;

		if (runningAt instanceof EthereumNode) {
			ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
			scheduler.scheduleAtFixedRate(() -> {
				try {
					localServices.forEach((name, versionsMap) -> versionsMap.forEach((version, instance) ->
							((EthereumNode) runningAt).announceServiceDeployment(instance.getService())));
				} catch (ConcurrentModificationException e) {
					// this is fine, it just means the service stopped
					// well, there might be an "announcement race condition", so to speak
					// which isn't nice, but not terrible
					// TODO: only change if this occurs often
				}
			}, 1, 1, TimeUnit.MINUTES);
			// TODO: for now this is nice for testing, but eventually something like 5min would be okay
		}
	}

	public void setWaitForResults(int c) {
		this.waitForResults = c;
	}

	public void setLifeTimeSeconds(int c) {
		this.lifeTimeSeconds = c;
	}

	public void setTimeoutMs(int timeoutMs) {
		this.timeoutMs = timeoutMs;
	}

	/**
	 * clears the global cache (needed for units tests)
	 */
	public void clear() {
		this.globalServices.clear();
	}

	/**
	 * returns a service agent instance. whenever possible, a local version is preferred.
	 * 
	 * it returns the newest version "available", where "available" does not refer to the network but to the set of
	 * service instances matching other parameters (node load, response time, ...)
	 * 
	 * @param service the requested service
	 * @param exact forces an exact version match
	 * @param localOnly only look for local services
	 * @param acting an acting agent invoking the service
	 * @return any service agent matching the requirements
	 * @throws AgentNotRegisteredException If the service agent is not registered
	 */
	public ServiceInstance getServiceAgentInstance(ServiceNameVersion service, boolean exact, boolean localOnly,
			AgentImpl acting) throws AgentNotRegisteredException {

		ServiceInstance local = null, global = null;

		// search locally
		if (exact) {
			synchronized (localServices) {
				if (localServices.containsKey(service.getName())
						&& localServices.get(service.getName()).containsKey(service.getVersion())) {
					local = localServices.get(service.getName()).get(service.getVersion());
				}
			}
		} else {
			synchronized (localServices) {
				if (localServices.containsKey(service.getName())) {
					for (Map.Entry<ServiceVersion, ServiceInstance> e : localServices.get(service.getName())
							.entrySet()) {
						if (e.getKey().fits(service.getVersion())) {
							local = e.getValue();
							break;
						}
					}
				}
			}
		}

		// search globally
		if (!localOnly && (local == null || runningAt.isBusy())) {
			if (exact) {
				ServiceInstance instance = getBestGlobalInstanceOfVersion(service.getName(), service.getVersion());

				if (instance == null) {
					try {
						update(service, true, acting);
					} catch (Exception e) {
						if (e instanceof TimeoutException) {
							logger.log(Level.INFO, "Timeout while updating service cache. " + e.toString());
						} else {
							logger.log(Level.INFO, "Could not update service cache", e);
						}
						if (local == null) {
							throw new AgentNotRegisteredException(
									"Could not retrieve service information from the network.", e);
						}
					}
					instance = getBestGlobalInstanceOfVersion(service.getName(), service.getVersion());
				}

				if (instance != null) {
					global = instance;
				}
			} else {
				ServiceInstance instance = getBestGlobalInstanceFitsVersion(service.getName(), service.getVersion());
				if (instance != null) {
					global = instance;
				}

				if (instance == null) {
					try {
						update(service, false, acting);
					} catch (Exception e) {
						if (e instanceof TimeoutException) {
							logger.log(Level.INFO, "Timeout while updating service cache. " + e.toString());
						} else {
							logger.log(Level.INFO, "Could not update service cache", e);
						}
						if (local == null) {
							throw new AgentNotRegisteredException(
									"Could not retrieve service information from the network.", e);
						}
					}
					instance = getBestGlobalInstanceFitsVersion(service.getName(), service.getVersion());
				}

				if (instance != null) {
					global = instance;
				}
			}
		}

		if (local != null && (!runningAt.isBusy() || global == null)) {
			return local;
		} else if (global != null) {
			return global;
		}

		throw new AgentNotRegisteredException("Could not find any agent for this service on the network!");
	}

	private ServiceInstance getBestGlobalInstanceFitsVersion(String name, ServiceVersion version) {
		synchronized (globalServices) {
			ServiceInstance result = null;

			if (globalServices.containsKey(name)) {
				SortedMap<ServiceVersion, SortedSet<ServiceInstance>> versions = globalServices.get(name);
				Iterator<Map.Entry<ServiceVersion, SortedSet<ServiceInstance>>> it_versions = versions.entrySet()
						.iterator();

				while (it_versions.hasNext()) {
					Map.Entry<ServiceVersion, SortedSet<ServiceInstance>> e = it_versions.next();

					if (e.getKey().fits(version)) {
						Iterator<ServiceInstance> it_instances = e.getValue().iterator();
						while (it_instances.hasNext()) {
							ServiceInstance i = it_instances.next();
							if (!i.outdated()) {
								if (result == null) {
									result = i;
								}
							} else {
								it_instances.remove();
							}
						}
						if (e.getValue().isEmpty()) {
							it_versions.remove();
						}
					}
				}

				if (versions.isEmpty()) {
					globalServices.remove(name);
				}
			}

			return result;
		}
	}

	private ServiceInstance getBestGlobalInstanceOfVersion(String name, ServiceVersion version) {
		synchronized (globalServices) {
			ServiceInstance result = null;

			if (globalServices.containsKey(name) && globalServices.get(name).containsKey(version)) {
				SortedSet<ServiceInstance> instances = globalServices.get(name).get(version);
				Iterator<ServiceInstance> it_instances = instances.iterator();
				while (it_instances.hasNext()) {
					ServiceInstance i = it_instances.next();
					if (!i.outdated()) {
						if (result == null) {
							result = i;
						}
					} else {
						it_instances.remove();
					}
				}

				if (instances.isEmpty()) {
					globalServices.get(name).remove(version);
				}
				if (globalServices.get(name).isEmpty()) {
					globalServices.remove(name);
				}
			}

			return result;
		}
	}

	/**
	 * adds a global service instance
	 * 
	 * @param instance
	 */
	private void addGlobalServiceInstance(ServiceInstance instance) {
		synchronized (globalServices) {
			SortedMap<ServiceVersion, SortedSet<ServiceInstance>> versions = globalServices
					.get(instance.service.getName());

			if (versions == null) {
				versions = new TreeMap<>(Comparator.comparing((ServiceVersion s) -> s).reversed());
				globalServices.put(instance.service.getName(), versions);
			}

			SortedSet<ServiceInstance> instances = versions.get(instance.service.getVersion());
			if (instances == null) {
				instances = new TreeSet<>();
				versions.put(instance.service.getVersion(), instances);
			}

			if (!instances.contains(instance)) {
				instances.add(instance);
			}
		}
	}

	/**
	 * removes a global service instance
	 * 
	 * to be called when an instance is detected as not available
	 * 
	 * @param instance A service instance to remove
	 */
	public void removeGlobalServiceInstance(ServiceInstance instance) {
		synchronized (globalServices) {
			if (globalServices.containsKey(instance.service.getName())
					&& globalServices.get(instance.service.getName()).containsKey(instance.service.getVersion())) {
				globalServices.get(instance.service.getName()).get(instance.service.getVersion()).remove(instance);

				if (globalServices.get(instance.service.getName()).get(instance.service.getVersion()).size() == 0) {
					globalServices.get(instance.service.getName()).remove(instance.service.getVersion());

					if (globalServices.get(instance.service.getName()).size() == 0) {
						globalServices.remove(instance.service.getName());
					}
				}
			}
		}
	}

	/**
	 * start a search for services in the network
	 * 
	 * @param service
	 * @param exact
	 * @param acting
	 * @return true if instances have been found
	 * @throws EncodingFailedException
	 * @throws InternalSecurityException
	 * @throws SerializationException
	 * @throws InterruptedException
	 * @throws TimeoutException
	 */
	private boolean update(ServiceNameVersion service, boolean exact, AgentImpl acting) throws EncodingFailedException,
			InternalSecurityException, SerializationException, InterruptedException, TimeoutException {

		Message m = new Message(acting, ServiceAgentImpl.serviceNameToTopicId(service.getName()),
				new ServiceDiscoveryContent(service, exact), timeoutMs);
		m.setSendingNodeId(runningAt.getNodeId());
		Message[] results = runningAt.sendMessageAndCollectAnswers(m, waitForResults);

		if (results.length > 0) {
			boolean added = false;
			long i = 1;
			for (Message res : results) {
				try {
					res.open(acting, runningAt);
				} catch (Exception e) {
					logger.log(Level.WARNING, "Could not open service response message", e);
					continue;
				}

				if (!(res.getContent() instanceof ServiceDiscoveryContent)) {
					logger.log(Level.INFO, "Response content is not " + ServiceDiscoveryContent.class.getCanonicalName()
							+ " got " + res.getContent().getClass().getCanonicalName() + " instead");
					continue;
				}

				ServiceInstance instance = new ServiceInstance(
						((ServiceDiscoveryContent) res.getContent()).getService(), res.getSenderId(),
						res.getSendingNodeId());

				// ok, should be replaced by a real measure in future...
				instance.responseTimeMs = i;

				addGlobalServiceInstance(instance);

				added = true;

				i++;
			}

			return added;
		} else {
			return false;
		}
	}

	/**
	 * register a local service
	 * 
	 * @param agent A service agent to register
	 */
	public void registerLocalService(ServiceAgentImpl agent) {
		synchronized (localServices) {
			ServiceNameVersion service = agent.getServiceNameVersion();

			SortedMap<ServiceVersion, ServiceInstance> versions = localServices.get(service.getName());
			if (versions == null) {
				versions = new TreeMap<>(Comparator.comparing((ServiceVersion s) -> s).reversed());
				localServices.put(service.getName(), versions);
			}

			if (!versions.containsKey(service.getVersion())) {
				versions.put(service.getVersion(), new ServiceInstance(agent));
			} else if (versions.get(service.getVersion()).getServiceAgent() != agent) {
				throw new IllegalStateException(
						"Another ServiceAgent running the same Service is present on this Node - something went wrong!");
			}
		}
		if (runningAt instanceof EthereumNode) {
			((EthereumNode) runningAt).announceServiceDeployment(agent);
		}
	}

	/**
	 * unregister a local service
	 * 
	 * @param agent A service agent to unregister
	 */
	public void unregisterLocalService(ServiceAgentImpl agent) {
		synchronized (localServices) {
			ServiceNameVersion service = agent.getServiceNameVersion();

			Map<ServiceVersion, ServiceInstance> versions = localServices.get(service.getName());
			if (versions == null) {
				return;
			}
			if (versions.get(service.getVersion()) == null) {
				return;
			}
			if (versions.get(service.getVersion()).getServiceAgent() != agent) {
				throw new IllegalStateException(
						"Another ServiceAgent running the same Service is present on this Node - something went wrong!");
			}
			versions.remove(service.getVersion());
			if (versions.size() == 0) {
				localServices.remove(service.getName());
			}
		}
		if (runningAt instanceof EthereumNode) {
			((EthereumNode) runningAt).announceServiceDeploymentEnd(agent);
		}
	}

	/**
	 * Gets a locally registered service agent
	 * 
	 * @param service name and exact version of the service
	 * @return Returns the local service agent instance
	 * @throws AgentNotRegisteredException If no agent is registered
	 */
	public ServiceAgentImpl getLocalService(ServiceNameVersion service) throws AgentNotRegisteredException {
		synchronized (localServices) {
			Map<ServiceVersion, ServiceInstance> versions = localServices.get(service.getName());
			if (versions == null) {
				throw new AgentNotRegisteredException("No local agent registered for this service!");
			}
			ServiceInstance instance = versions.get(service.getVersion());
			if (instance == null) {
				throw new AgentNotRegisteredException("No local agent registered for this service!");
			}
			return instance.getServiceAgent();
		}
	}

	public List<String> getLocalServiceNames() {
		ArrayList<String> result = new ArrayList<>();
		synchronized (localServices) {
			if (localServices != null) {
				result = new ArrayList<>(localServices.keySet());
			}
		}
		return result;
	}

	public List<ServiceVersion> getLocalServiceVersions(String serviceName) {
		ArrayList<ServiceVersion> result = new ArrayList<>();
		synchronized (localServices) {
			if (localServices != null) {
				SortedMap<ServiceVersion, ServiceInstance> versions = localServices.get(serviceName);
				if (versions != null) {
					result.addAll(versions.keySet());
				}
			}
		}
		return result;
	}

	/**
	 * represents an instance of a service agent
	 *
	 */
	public class ServiceInstance implements Comparable<ServiceInstance> {
		private ServiceNameVersion service;

		private boolean isLocal;

		private ServiceAgentImpl agent;

		String serviceAgentId;
		Object nodeId;
		long responseTimeMs;
		private long lastSeen;

		/**
		 * create a local service instance
		 * 
		 * @param agent A service agent
		 */
		public ServiceInstance(ServiceAgentImpl agent) {
			this.isLocal = true;
			this.agent = agent;
			this.service = agent.getServiceNameVersion();
			touch();
		}

		/**
		 * create a global service instance
		 * 
		 * @param service A service name and version
		 * @param serviceAgentId A service agent id
		 * @param nodeId A node id
		 */
		public ServiceInstance(ServiceNameVersion service, String serviceAgentId, Object nodeId) {
			this.service = service;
			this.isLocal = false;
			this.serviceAgentId = serviceAgentId;
			this.nodeId = nodeId;
			touch();
		}

		@Override
		public boolean equals(Object other) {
			if (!(other instanceof ServiceInstance)) {
				return false;
			}

			ServiceInstance o = (ServiceInstance) other;

			if (this.isLocal != o.isLocal) {
				return false;
			}

			if (this.isLocal) {
				return this.agent.equals(o.agent);
			} else {
				return this.serviceAgentId == o.serviceAgentId && this.nodeId == o.nodeId;
			}
		}

		@Override
		public int compareTo(ServiceInstance other) {
			// this decides which service agents are preferred
			if (other == null) {
				throw new NullPointerException();
			}

			if (this.responseTimeMs < other.responseTimeMs) {
				return -1;
			} else if (this.responseTimeMs == other.responseTimeMs) {
				return 0;
			} else {
				// (this.responseTimeMs > other.responseTimeMs)
				return 1;
			}
		}

		public void touch() {
			this.lastSeen = System.currentTimeMillis() / 1000L;
		}

		public boolean outdated() {
			return (lastSeen + lifeTimeSeconds) < (System.currentTimeMillis() / 1000L);
		}

		public boolean local() {
			return isLocal;
		}

		public ServiceAgentImpl getServiceAgent() {
			return agent;
		}

		public ServiceNameVersion getService() {
			return service;
		}

		public String getServiceAgentId() {
			return serviceAgentId;
		}

		public Object getNodeId() {
			return nodeId;
		}
	}

}
