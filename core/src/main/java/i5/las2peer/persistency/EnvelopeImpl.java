package i5.las2peer.persistency;

import java.io.Serializable;
import java.util.HashSet;
import java.util.Set;

import i5.las2peer.api.persistency.Envelope;
import i5.las2peer.api.persistency.EnvelopeAccessDeniedException;
import i5.las2peer.api.security.Agent;
import i5.las2peer.api.security.AnonymousAgent;
import i5.las2peer.security.AgentContext;
import i5.las2peer.security.AgentImpl;
import i5.las2peer.serialization.SerializationException;
import i5.las2peer.tools.CryptoException;
import i5.las2peer.tools.CryptoTools;

public class EnvelopeImpl implements Envelope {

	private String identifier;
	private Serializable content;

	private Set<AgentImpl> readerToAdd = new HashSet<>();
	private Set<AgentImpl> readerToRevoke = new HashSet<>();
	private boolean revokeAllReaders = false;
	private String signingAgentId;

	private EnvelopeVersion currentVersion;

	public EnvelopeImpl(String identifier, AgentImpl signingAgent) throws EnvelopeAccessDeniedException {
		this.identifier = identifier;
		this.content = null;
		this.currentVersion = null;
		this.signingAgentId = signingAgent.getIdentifier();
		if (signingAgent instanceof AnonymousAgent) {
			throw new EnvelopeAccessDeniedException("Anonymous agent must not be used to sign an envelope");
		}
		this.readerToAdd.add(signingAgent);
	}

	public EnvelopeImpl(EnvelopeVersion currentVersion, AgentContext context)
			throws CryptoException, EnvelopeAccessDeniedException, SerializationException {
		String id = currentVersion.getIdentifier();
		int sepIndex = id.indexOf("$");
		if (sepIndex == -1) {
			this.identifier = id;
		} else {
			this.identifier = id.substring(sepIndex);
		}
		this.content = currentVersion.getContent(context);
		this.currentVersion = currentVersion;
		this.signingAgentId = CryptoTools.publicKeyToSHA512(currentVersion.getAuthorPublicKey());
	}

	@Override
	public String getIdentifier() {
		return this.identifier;
	}

	@Override
	public Serializable getContent() {
		return content;
	}

	@Override
	public void setContent(Serializable content) {
		this.content = content;

	}

	@Override
	public void addReader(Agent agent) {
		if (agent instanceof AnonymousAgent) {
			throw new IllegalArgumentException(
					"Anonymous agent must not be given read access. Set the envelope instance public instead.");
		}
		this.readerToAdd.add((AgentImpl) agent);
		this.readerToRevoke.remove(agent);
	}

	@Override
	public void revokeReader(Agent agent) {
		this.readerToAdd.remove(agent);
		this.readerToRevoke.add((AgentImpl) agent);
	}

	@Override
	public boolean hasReader(Agent agent) {
		return (currentVersion != null && currentVersion.getReaderKeys().containsKey(((AgentImpl) agent).getPublicKey())
				|| readerToAdd.contains(agent)) && !readerToRevoke.contains(agent) && !revokeAllReaders;
	}

	@Override
	public void setPublic() {
		revokeAllReaders = true;
		readerToAdd.clear();
		readerToRevoke.clear();
	}

	@Override
	public boolean isPrivate() {
		return (currentVersion == null && !readerToAdd.isEmpty())
				|| (currentVersion != null && currentVersion.isEncrypted() && !revokeAllReaders)
				|| (currentVersion != null && !currentVersion.isEncrypted() && !readerToAdd.isEmpty());
	}

	public Set<AgentImpl> getReaderToAdd() {
		return readerToAdd;
	}

	public Set<AgentImpl> getReaderToRevoke() {
		return readerToRevoke;
	}

	public boolean getRevokeAllReaders() {
		return revokeAllReaders;
	}

	public EnvelopeVersion getVersion() {
		return this.currentVersion;
	}

	public void setVersion(EnvelopeVersion v) {
		this.currentVersion = v;
		this.revokeAllReaders = false;
		this.readerToAdd.clear();
		this.readerToRevoke.clear();
	}

	@Override
	public String getOwnerId() {
		return this.signingAgentId;
	}

}
