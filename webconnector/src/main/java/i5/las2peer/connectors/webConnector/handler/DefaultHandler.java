package i5.las2peer.connectors.webConnector.handler;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Date;

import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriInfo;

import org.glassfish.jersey.media.multipart.ContentDisposition;

import i5.las2peer.api.p2p.ServiceNameVersion;
import i5.las2peer.api.security.Agent;
import i5.las2peer.connectors.webConnector.WebConnector;
import i5.las2peer.connectors.webConnector.util.AuthenticationManager;
import i5.las2peer.connectors.webConnector.util.KeystoreManager;
import i5.las2peer.p2p.Node;
import i5.las2peer.p2p.PastryNodeImpl;
import i5.las2peer.restMapper.RESTService;
import i5.las2peer.security.AgentImpl;
import i5.las2peer.security.ServiceAgentImpl;
import i5.las2peer.serialization.SerializationException;
import i5.las2peer.tools.L2pNodeLauncher;

import net.minidev.json.JSONArray;
import net.minidev.json.JSONObject;

@Path(DefaultHandler.ROOT_RESOURCE_PATH)
public class DefaultHandler {

	public static final String ROOT_RESOURCE_PATH = "/las2peer";

	private final WebConnector connector;
	private final Node node;

	public DefaultHandler(WebConnector connector) {
		this.connector = connector;
		node = connector.getL2pNode();
	}

	@GET
	public Response rootPath() throws URISyntaxException {
		return Response.temporaryRedirect(new URI(WebappHandler.DEFAULT_ROUTE)).build();
	}

	@GET
	@Path("/version")
	@Produces(MediaType.TEXT_PLAIN)
	public String getCoreVersion() {
		return L2pNodeLauncher.getVersion();
	}

	@GET
	@Path("/cacert")
	public Response getCACert() throws IOException {
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			OutputStreamWriter osw = new OutputStreamWriter(baos);
			KeystoreManager.writeCertificateToPEMStream(connector.getCACertificate(), osw);
			osw.close();
			return Response.ok(baos.toByteArray(), "application/x-pem-file")
					.header(HttpHeaders.CONTENT_DISPOSITION,
							ContentDisposition.type("attachment").fileName(connector.getRootCAFilename()).build())
					.build();
		} catch (FileNotFoundException e) {
			return Response.status(Status.NOT_FOUND).build();
		} catch (Exception e) {
			return Response.serverError().entity(e.toString()).build();
		}
	}

	@GET
	@Path("/status")
	@Produces(MediaType.APPLICATION_JSON)
	public String getNodeStatus(@Context UriInfo uriInfo) {
		// gather status values and return as JSON string
		JSONObject response = new JSONObject();
		response.put("nodeId", node.getNodeId().toString());
		response.put("cpuLoad", getCPULoad(node));
		long localStorageSize = -1;
		long maxLocalStorageSize = -1;
		if (node instanceof PastryNodeImpl) {
			localStorageSize = ((PastryNodeImpl) node).getLocalStorageSize();
			maxLocalStorageSize = ((PastryNodeImpl) node).getLocalMaxStorageSize();
		}
		response.put("storageSize", localStorageSize);
		response.put("storageSizeStr", humanReadableByteCount(localStorageSize, true));
		response.put("maxStorageSize", maxLocalStorageSize);
		response.put("maxStorageSizeStr", humanReadableByteCount(maxLocalStorageSize, true));
		response.put("uptime", getUptime(node));
		response.put("localServices", getLocalServices(node, uriInfo.getRequestUri()));
		response.put("otherNodes", getOtherNodes(node));
		return response.toJSONString();
	}

	private int getCPULoad(Node node) {
		return (int) (node.getNodeCpuLoad() * 100);
	}

	// Source: http://stackoverflow.com/questions/3758606/how-to-convert-byte-size-into-human-readable-format-in-java
	private static String humanReadableByteCount(long bytes, boolean si) {
		int unit = si ? 1000 : 1024;
		if (bytes < unit) {
			return bytes + " B";
		}
		int exp = (int) (Math.log(bytes) / Math.log(unit));
		String pre = (si ? "kMGTPE" : "KMGTPE").charAt(exp - 1) + (si ? "" : "i");
		return String.format("%.1f %sB", bytes / Math.pow(unit, exp), pre);
	}

	private String getUptime(Node node) {
		Date startTime = node.getStartTime();
		if (startTime == null) {
			return "node stopped";
		} else {
			long uptimeInSeconds = (new Date().getTime() - node.getStartTime().getTime()) / 1000;
			long hours = uptimeInSeconds / 3600;
			long minutes = uptimeInSeconds / 60 % 60;
			long seconds = uptimeInSeconds % 60;
			return hours + ":" + String.format("%02d", minutes) + ":" + String.format("%02d", seconds);
		}
	}

	private JSONArray getLocalServices(Node node, URI requestURI) {
		JSONArray result = new JSONArray();
		ServiceAgentImpl[] localServices = node.getRegisteredServices();
		for (ServiceAgentImpl localServiceAgent : localServices) {
			try {
				ServiceNameVersion nameVersion = localServiceAgent.getServiceNameVersion();
				JSONObject json = new JSONObject();
				json.put("name", nameVersion.getName());
				json.put("version", nameVersion.getVersion().toString());
				// use host header, so browsers do not block subsequent ajax requests to an unknown host
				String swaggerStr = "";
				if (localServiceAgent.getServiceInstance() instanceof RESTService) {
					String serviceAlias = localServiceAgent.getServiceInstance().getAlias();
					URI swaggerURI = new URI(requestURI.getScheme(), null, requestURI.getHost(), requestURI.getPort(),
							"/" + serviceAlias + "/v" + nameVersion.getVersion().toString() + "/swagger.json", null,
							null);
					swaggerStr = swaggerURI.toString();
				}
				json.put("swagger", swaggerStr);
				result.add(json);
			} catch (Exception e) {
				// XXX logging
				e.printStackTrace();
			}
		}
		return result;
	}

	private JSONArray getOtherNodes(Node node) {
		JSONArray result = new JSONArray();
		for (Object other : node.getOtherKnownNodes()) {
			result.add(other.toString());
		}
		return result;
	}

	@GET
	@Produces(MediaType.TEXT_PLAIN)
	@Path("/currentagent")
	public String getCurrentAgentId(@Context UriInfo uriInfo, @Context HttpHeaders httpHeaders) {
		Agent agent = connector.authenticateAgent(httpHeaders.getRequestHeaders(),
				uriInfo.getQueryParameters().getFirst(AuthenticationManager.ACCESS_TOKEN_KEY));
		if (agent == null) {
			throw new InternalServerErrorException("Authorization failed");
		} else {
			return agent.getIdentifier();
		}
	}

	@GET
	@Produces(MediaType.APPLICATION_JSON)
	@Path("/currentagent")
	public String getCurrentAgentJson(@Context UriInfo uriInfo, @Context HttpHeaders httpHeaders) {
		Agent agent = connector.authenticateAgent(httpHeaders.getRequestHeaders(),
				uriInfo.getQueryParameters().getFirst(AuthenticationManager.ACCESS_TOKEN_KEY));
		if (agent == null) {
			return null;
		} else {
			JSONObject json = new JSONObject();
			json.put("agentid", agent.getIdentifier());
			return json.toJSONString();
		}
	}

	@GET
	@Produces(MediaType.APPLICATION_XML)
	@Path("/currentagent")
	public String getCurrentAgentXml(@Context UriInfo uriInfo, @Context HttpHeaders httpHeaders) {
		AgentImpl agent = connector.authenticateAgent(httpHeaders.getRequestHeaders(),
				uriInfo.getQueryParameters().getFirst(AuthenticationManager.ACCESS_TOKEN_KEY));
		if (agent == null) {
			throw new InternalServerErrorException("Authorization failed");
		} else {
			try {
				return agent.toXmlString();
			} catch (SerializationException e) {
				throw new InternalServerErrorException(e);
			}
		}
	}
}
