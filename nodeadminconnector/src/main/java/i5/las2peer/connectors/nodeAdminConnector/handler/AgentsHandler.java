package i5.las2peer.connectors.nodeAdminConnector.handler;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.logging.Level;

import javax.ws.rs.BadRequestException;
import javax.ws.rs.CookieParam;
import javax.ws.rs.FormParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.ServerErrorException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import org.glassfish.jersey.media.multipart.FormDataParam;

import i5.las2peer.api.security.AgentAccessDeniedException;
import i5.las2peer.api.security.AgentException;
import i5.las2peer.api.security.AgentNotFoundException;
import i5.las2peer.connectors.nodeAdminConnector.AgentSession;
import i5.las2peer.connectors.nodeAdminConnector.NodeAdminConnector;
import i5.las2peer.security.AgentImpl;
import i5.las2peer.security.GroupAgentImpl;
import i5.las2peer.security.PassphraseAgentImpl;
import i5.las2peer.security.UserAgentImpl;
import i5.las2peer.serialization.SerializationException;
import i5.las2peer.tools.CryptoException;
import net.minidev.json.JSONArray;
import net.minidev.json.JSONObject;
import net.minidev.json.parser.JSONParser;
import net.minidev.json.parser.ParseException;

@Path("/agents")
public class AgentsHandler extends AbstractHandler {

	public AgentsHandler(NodeAdminConnector connector) {
		super(connector);
	}

	@POST
	@Path("/createAgent")
	@Produces(MediaType.APPLICATION_JSON)
	public Response handleCreateAgent(@FormParam("password") String password, @FormParam("username") String username,
			@FormParam("email") String email) throws Exception {
		if (password == null || password.isEmpty()) {
			return Response.status(Status.BAD_REQUEST).entity("No password provided").build();
		}
		if (username != null && !username.isEmpty()) {
			// check if username is already taken
			try {
				node.getAgentIdForLogin(username);
				return Response.status(Status.BAD_REQUEST).entity("Username already taken").build();
			} catch (AgentNotFoundException e) {
				// expected
			}
		}
		if (email != null && !email.isEmpty()) {
			// check if email is already taken
			try {
				node.getAgentIdForEmail(email);
				return Response.status(Status.BAD_REQUEST).entity("Email already taken").build();
			} catch (AgentNotFoundException e) {
				// expected
			}
		}
		// create new user agent and store in network
		UserAgentImpl agent = UserAgentImpl.createUserAgent(password);
		agent.unlock(password);
		if (username != null && !username.isEmpty()) {
			agent.setLoginName(username);
		}
		if (email != null && !email.isEmpty()) {
			agent.setEmail(email);
		}
		node.storeAgent(agent);
		JSONObject json = new JSONObject();
		json.put("code", Status.OK.getStatusCode());
		json.put("text", Status.OK.getStatusCode() + " - Agent created");
		json.put("agentid", agent.getIdentifier());
		json.put("username", agent.getLoginName());
		json.put("email", agent.getEmail());
		return Response.ok(json.toJSONString(), MediaType.APPLICATION_JSON).build();
	}

	@POST
	@Path("/getAgent")
	@Produces(MediaType.APPLICATION_JSON)
	public Response handleGetAgent(@FormParam("agentid") String agentId, @FormParam("username") String username,
			@FormParam("email") String email) throws Exception {
		AgentImpl agent = getAgentByDetail(agentId, username, email);
		JSONObject json = new JSONObject();
		json.put("agentid", agent.getIdentifier());
		if (agent instanceof UserAgentImpl) {
			UserAgentImpl userAgent = (UserAgentImpl) agent;
			json.put("username", userAgent.getLoginName());
			json.put("email", userAgent.getEmail());
		}
		return Response.ok(json.toJSONString(), MediaType.APPLICATION_JSON).build();
	}

	@POST
	@Path("/exportAgent")
	public Response handleExportAgent(@FormParam("agentid") String agentId, @FormParam("username") String username,
			@FormParam("email") String email) throws Exception {
		AgentImpl agent = getAgentByDetail(agentId, username, email);
		return Response.ok(agent.toXmlString(), MediaType.APPLICATION_XML).build();
	}

	private AgentImpl getAgentByDetail(String agentId, String username, String email) throws Exception {
		try {
			if (agentId == null || agentId.isEmpty()) {
				if (username != null && !username.isEmpty()) {
					agentId = node.getAgentIdForLogin(username);
				} else if (email != null && !email.isEmpty()) {
					agentId = node.getAgentIdForEmail(email);
				} else {
					throw new BadRequestException("No required agent detail provided");
				}
			}
			return node.getAgent(agentId);
		} catch (AgentNotFoundException e) {
			throw new BadRequestException("Agent not found");
		}
	}

	@POST
	@Path("/uploadAgent")
	public Response handleUploadAgent(@FormDataParam("agentFile") InputStream formPart,
			@FormDataParam("agentPassword") String password,
			@CookieParam(NodeAdminConnector.COOKIE_SESSIONID_KEY) String sessionId) throws Exception {
		if (formPart == null) {
			return Response.status(Status.BAD_REQUEST).entity("No agent file provided").build();
		}
		AgentImpl agent = AgentImpl.createFromXml(formPart);
		if (agent instanceof PassphraseAgentImpl) {
			PassphraseAgentImpl passphraseAgent = (PassphraseAgentImpl) agent;
			if (password == null) {
				return Response.status(Status.BAD_REQUEST).entity("No password provided").build();
			}
			try {
				passphraseAgent.unlock(password);
			} catch (AgentAccessDeniedException e) {
				return Response.status(Status.BAD_REQUEST).entity("Invalid agent password").build();
			}
		} else if (agent instanceof GroupAgentImpl) {
			GroupAgentImpl groupAgent = (GroupAgentImpl) agent;
			AgentSession session = connector.getSessionById(sessionId);
			if (session == null) {
				return Response.status(Status.BAD_REQUEST)
						.entity("You have to be logged in, to unlock and update a group").build();
			}
			try {
				groupAgent.unlock(session.getAgent());
			} catch (AgentAccessDeniedException e) {
				return Response.status(Status.FORBIDDEN).entity("You have to be a member of the uploaded group")
						.build();
			}
		} else {
			return Response.status(Status.BAD_REQUEST)
					.entity("Invalid agent type '" + agent.getClass().getSimpleName() + "'").build();
		}
		node.storeAgent(agent);
		JSONObject json = new JSONObject();
		json.put("code", Status.OK.getStatusCode());
		json.put("text", Status.OK.getStatusCode() + " - Agent uploaded");
		json.put("agentid", agent.getIdentifier());
		return Response.ok(json.toJSONString(), MediaType.APPLICATION_JSON).build();
	}

	@POST
	@Path("/changePassphrase")
	public Response handleChangePassphrase(@FormParam("agentid") String agentId,
			@FormParam("passphrase") String passphrase, @FormParam("passphraseNew") String passphraseNew,
			@FormParam("passphraseNew2") String passphraseNew2) throws Exception {
		if (agentId == null || agentId.isEmpty()) {
			return Response.status(Status.BAD_REQUEST).entity("No agentid provided").build();
		}
		AgentImpl agent;
		try {
			agent = node.getAgent(agentId);
		} catch (AgentNotFoundException e) {
			return Response.status(Status.BAD_REQUEST).entity("Agent not found").build();
		}
		if (!(agent instanceof PassphraseAgentImpl)) {
			return Response.status(Status.BAD_REQUEST).entity("Invalid agent type").build();
		}
		PassphraseAgentImpl passAgent = (PassphraseAgentImpl) agent;
		if (passphrase == null || passphrase.isEmpty()) {
			return Response.status(Status.BAD_REQUEST).entity("No passphrase provided").build();
		}
		try {
			passAgent.unlock(passphrase);
		} catch (AgentAccessDeniedException e) {
			return Response.status(Status.BAD_REQUEST).entity("Invalid passphrase").build();
		}
		if (passphraseNew == null || passphraseNew.isEmpty()) {
			return Response.status(Status.BAD_REQUEST).entity("No passphrase to change provided").build();
		}
		if (passphraseNew2 == null || passphraseNew2.isEmpty()) {
			return Response.status(Status.BAD_REQUEST).entity("No passphrase repetition provided").build();
		}
		if (!passphraseNew.equals(passphraseNew2)) {
			return Response.status(Status.BAD_REQUEST).entity("New passphrase and repetition do not match").build();
		}
		passAgent.changePassphrase(passphraseNew);
		node.storeAgent(passAgent);
		JSONObject json = new JSONObject();
		json.put("code", Status.OK.getStatusCode());
		json.put("text", Status.OK.getStatusCode() + " - Passphrase changed");
		json.put("agentid", agent.getIdentifier());
		return Response.ok(json.toJSONString(), MediaType.APPLICATION_JSON).build();
	}

	@POST
	@Path("/createGroup")
	public Response handleCreateGroup(@CookieParam(NodeAdminConnector.COOKIE_SESSIONID_KEY) String sessionId,
			@FormParam("members") String strMembers) throws Exception {
		AgentSession session = connector.getSessionById(sessionId);
		if (session == null) {
			return Response.status(Status.FORBIDDEN).entity("You have to be logged in to create a group").build();
		}
		if (strMembers == null) {
			return Response.status(Status.BAD_REQUEST).entity("No members provided").build();
		}
		JSONArray jsonMembers = (JSONArray) new JSONParser(JSONParser.DEFAULT_PERMISSIVE_MODE).parse(strMembers);
		if (jsonMembers.isEmpty()) {
			return Response.status(Status.BAD_REQUEST).entity("Members list empty").build();
		}
		ArrayList<AgentImpl> memberAgents = new ArrayList<>(jsonMembers.size());
		for (Object objMember : jsonMembers) {
			if (objMember instanceof JSONObject) {
				JSONObject jsonMember = (JSONObject) objMember;
				String agentId = jsonMember.getAsString("agentid");
				try {
					memberAgents.add(node.getAgent(agentId));
				} catch (Exception e) {
					throw new ServerErrorException("Could not get member " + agentId, Status.INTERNAL_SERVER_ERROR, e);
				}
			}
		}
		GroupAgentImpl groupAgent = GroupAgentImpl
				.createGroupAgent(memberAgents.toArray(new AgentImpl[memberAgents.size()]));
		groupAgent.unlock(session.getAgent());
		node.storeAgent(groupAgent);
		JSONObject json = new JSONObject();
		json.put("code", Status.OK.getStatusCode());
		json.put("text", Status.OK.getStatusCode() + " - GroupAgent created");
		json.put("agentid", groupAgent.getIdentifier());
		return Response.ok(json.toJSONString(), MediaType.APPLICATION_JSON).build();
	}

	@POST
	@Path("/loadGroup")
	public Response handleLoadGroup(@CookieParam(NodeAdminConnector.COOKIE_SESSIONID_KEY) String sessionId,
			@FormParam("agentid") String agentId) throws AgentException {
		AgentSession session = connector.getSessionById(sessionId);
		if (session == null) {
			return Response.status(Status.FORBIDDEN).entity("You have to be logged in to load a group").build();
		}
		if (agentId == null || agentId.isEmpty()) {
			return Response.status(Status.BAD_REQUEST).entity("No agent id provided").build();
		}
		AgentImpl agent;
		try {
			agent = node.getAgent(agentId);
		} catch (AgentNotFoundException e) {
			return Response.status(Status.BAD_REQUEST).entity("Agent not found").build();
		}
		if (!(agent instanceof GroupAgentImpl)) {
			return Response.status(Status.BAD_REQUEST).entity("Agent is not a GroupAgent").build();
		}
		GroupAgentImpl groupAgent = (GroupAgentImpl) agent;
		try {
			groupAgent.unlock(session.getAgent());
		} catch (AgentAccessDeniedException e) {
			return Response.status(Status.BAD_REQUEST).entity("You must be a member of this group").build();
		}
		JSONObject json = new JSONObject();
		json.put("code", Status.OK.getStatusCode());
		json.put("text", Status.OK.getStatusCode() + " - GroupAgent loaded");
		json.put("agentid", groupAgent.getIdentifier());
		JSONArray memberList = new JSONArray();
		for (String memberid : groupAgent.getMemberList()) {
			JSONObject member = new JSONObject();
			member.put("agentid", memberid);
			try {
				AgentImpl memberAgent = node.getAgent(memberid);
				if (memberAgent instanceof UserAgentImpl) {
					UserAgentImpl memberUserAgent = (UserAgentImpl) memberAgent;
					member.put("username", memberUserAgent.getLoginName());
					member.put("email", memberUserAgent.getEmail());
				}
			} catch (AgentException e) {
				logger.log(Level.WARNING, "Could not retrieve group member agent from network", e);
			}
			memberList.add(member);
		}
		json.put("members", memberList);
		return Response.ok(json.toJSONString(), MediaType.APPLICATION_JSON).build();
	}

	@POST
	@Path("/changeGroup")
	public Response handleChangeGroup(@CookieParam(NodeAdminConnector.COOKIE_SESSIONID_KEY) String sessionId,
			@FormParam("agentid") String agentId, @FormParam("members") String strMembers)
			throws AgentException, CryptoException, SerializationException, ParseException {
		AgentSession session = connector.getSessionById(sessionId);
		if (session == null) {
			return Response.status(Status.FORBIDDEN).entity("You have to be logged in to change a group").build();
		}
		if (agentId == null || agentId.isEmpty()) {
			return Response.status(Status.BAD_REQUEST).entity("No agent id provided").build();
		}
		if (strMembers == null) {
			return Response.status(Status.BAD_REQUEST).entity("No members to change provided").build();
		}
		JSONArray changedMembers = (JSONArray) new JSONParser(JSONParser.DEFAULT_PERMISSIVE_MODE).parse(strMembers);
		if (changedMembers.isEmpty()) {
			return Response.status(Status.BAD_REQUEST).entity("Changed members list must not be empty").build();
		}
		AgentImpl agent;
		try {
			agent = node.getAgent(agentId);
		} catch (AgentNotFoundException e) {
			return Response.status(Status.BAD_REQUEST).entity("Agent not found").build();
		}
		if (!(agent instanceof GroupAgentImpl)) {
			return Response.status(Status.BAD_REQUEST).entity("Agent is not a GroupAgent").build();
		}
		GroupAgentImpl groupAgent = (GroupAgentImpl) agent;
		try {
			groupAgent.unlock(session.getAgent());
		} catch (AgentAccessDeniedException e) {
			return Response.status(Status.BAD_REQUEST).entity("You must be a member of this group").build();
		}
		// add new members
		HashSet<String> memberIds = new HashSet<>();
		for (Object obj : changedMembers) {
			if (obj instanceof JSONObject) {
				JSONObject json = (JSONObject) obj;
				String memberid = json.getAsString("agentid");
				if (memberid == null || memberid.isEmpty()) {
					logger.fine("Skipping invalid member id '" + memberid + "'");
					continue;
				}
				memberIds.add(memberid.toLowerCase());
				try {
					AgentImpl memberAgent = node.getAgent(memberid);
					groupAgent.addMember(memberAgent);
					logger.info("Added new member '" + memberid + "' to group");
				} catch (AgentException e) {
					logger.log(Level.WARNING, "Could not retrieve group member agent from network", e);
					continue;
				}
			} else {
				logger.info("Skipping invalid member object '" + obj.getClass().getCanonicalName() + "'");
			}
		}
		if (!memberIds.contains(session.getAgent().getIdentifier().toLowerCase())) {
			return Response.status(Status.BAD_REQUEST).entity("You can't remove yourself from a group").build();
		}
		// remove all non members
		for (String oldMemberId : groupAgent.getMemberList()) {
			if (!memberIds.contains(oldMemberId)) {
				groupAgent.removeMember(oldMemberId);
				logger.info("Removed old member '" + oldMemberId + "' from group");
			}
		}
		// store changed group
		node.storeAgent(groupAgent);
		JSONObject json = new JSONObject();
		json.put("code", Status.OK.getStatusCode());
		json.put("text", Status.OK.getStatusCode() + " - GroupAgent changed");
		json.put("agentid", groupAgent.getIdentifier());
		return Response.ok(json.toJSONString(), MediaType.APPLICATION_JSON).build();
	}

}
